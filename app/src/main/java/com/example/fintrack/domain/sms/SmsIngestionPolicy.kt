package com.example.fintrack.domain.sms

import java.security.MessageDigest

/**
 * Domain-owned SMS ingestion policy.
 *
 * Hard guardrails:
 *  - We never delete the user SMS database; we only read it.
 *  - Raw evidence is immutable once written.
 *  - Dedup is by provider id AND by sha-256(content|sender|timestamp).
 *  - Unknown fields stay unknown (null sender is preserved as null).
 */
object SmsIngestionPolicy {

    const val SOURCE_KIND_SMS_RECEIVED = "SMS_RECEIVED"
    const val SOURCE_KIND_BACKFILL = "BACKFILL"
    const val SOURCE_VERSION = "sms-v1"

    /** Aggressive but bounded page size for backfill batches. */
    const val BACKFILL_PAGE_SIZE = 100

    /** Cap to avoid unbounded scan; matches budgets, not policy. */
    const val BACKFILL_MAX_PAGES_PER_RUN = 200

    /** SHA-256 of sender|body|timestamp. Stable across processes. */
    fun contentHash(sender: String?, body: String, timestampEpochMs: Long): String {
        val sb = StringBuilder()
        sb.append(sender.orEmpty()).append('|')
        sb.append(body).append('|')
        sb.append(timestampEpochMs)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    enum class Status(val code: String) {
        IDLE("IDLE"),
        RUNNING("RUNNING"),
        PAUSED("PAUSED"),
        COMPLETE("COMPLETE"),
        REVOKED("REVOKED"),
        FAILED("FAILED");

        companion object {
            fun fromCode(code: String?): Status =
                entries.firstOrNull { it.code == code } ?: IDLE
        }
    }
}
