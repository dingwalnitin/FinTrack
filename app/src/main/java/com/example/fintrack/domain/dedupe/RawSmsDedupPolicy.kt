package com.example.fintrack.domain.dedupe

import com.example.fintrack.domain.sms.SmsIngestionPolicy

/**
 * P09 #1: RawSms dedup.
 *
 * Two layers:
 *  1. providerId (system SMS database _ID) is the primary key, but it is
 *     WEAK — Android can renumber after a backup/restore, factory reset,
 *     or app-data migration. We therefore never trust providerId alone.
 *  2. contentHash (sha-256 of sender|body|timestamp) is the durable
 *     cross-process identity. The unique index on raw_sms.contentHash
 *     catches providerId churn.
 *
 * The recovery path: when captureRaw fails to find the row by providerId
 * (i.e. providerId is new but contentHash is the same as an existing row),
 * the existing row's providerId is overwritten in-place so the durable
 * identity and the fresh providerId stay linked. This is the only allowed
 * write that touches an existing raw_sms row, and it never alters the
 * body / sender / timestamp / hash — only the providerId is refreshed.
 */
object RawSmsDedupPolicy {

    /**
     * Lookup-key resolution: which key should a new observation try first?
     * Always try providerId first (fast path). Only fall back to contentHash
     * when the new providerId is unknown — this avoids any race where the
     * same providerId is sent twice during a permission prompt.
     */
    fun firstKey(providerId: Long, contentHash: String): DedupKey =
        DedupKey.ProviderId(providerId, contentHash)

    /** True when an existing row's contentHash matches a new observation but providerId differs. */
    fun isWeakIdRecovery(
        existingProviderId: Long,
        newProviderId: Long,
        existingContentHash: String,
        newContentHash: String,
    ): Boolean =
        existingProviderId != newProviderId &&
            existingContentHash == newContentHash &&
            newContentHash.isNotBlank() &&
            existingContentHash == newContentHash
}

/** Lookup-key abstraction used by tests to verify dedup ordering. */
sealed class DedupKey {
    data class ProviderId(val providerId: Long, val contentHash: String) : DedupKey()
    data class ContentHash(val contentHash: String) : DedupKey()
}
