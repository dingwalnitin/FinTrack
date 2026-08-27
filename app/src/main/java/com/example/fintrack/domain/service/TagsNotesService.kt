package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.ReimbursementLink
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionNote
import com.example.fintrack.domain.model.TransactionTag
import com.example.fintrack.domain.model.TravelMode
import com.example.fintrack.domain.model.TravelModeStatus
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Stage 7 P15 — tags, notes, reimbursement links and travel modes.
 *
 * Tags are normalized (lowercase, trimmed) so search is deterministic.
 * Notes are free-form; the latest note per transaction is the one the
 * UI shows. Attachments remain placeholder-only per the Bible.
 *
 * Reimbursement links connect an expense to the credit that pays it
 * back. They never mutate either side. Idempotent on the pair.
 *
 * Travel mode is a per-account window with its own reporting currency;
 * transactions inside the window can be filtered as "travel" without
 * changing their stored currency or category.
 */
class TagsNotesService(
    private val sink: TagsNotesSink,
    private val clock: () -> Instant = Instant::now,
) {
    /** Monotonic guard so two notes written in the same millisecond still order deterministically. */
    private var lastNoteMs: Long = 0

    // ---- tags ----

    suspend fun addTag(transactionId: String, rawTag: String): Boolean {
        val tag = normalizeTag(rawTag)
        if (tag.isEmpty()) return false
        val now = clock().toEpochMilli()
        return sink.insertTransactionTag(
            TransactionTag(
                id = UUID.randomUUID().toString(),
                transactionId = transactionId,
                tag = tag,
                sourceKind = SourceKind.USER_CORRECTION,
                sourceVersion = "tags-v1",
                createdAtEpochMs = now,
            )
        )
    }

    suspend fun removeTag(transactionId: String, rawTag: String): Boolean =
        sink.deleteTransactionTag(transactionId, normalizeTag(rawTag))

    suspend fun tagsFor(transactionId: String): List<TransactionTag> =
        sink.tagsForTransaction(transactionId)

    private fun normalizeTag(raw: String): String = raw.trim().lowercase()

    // ---- notes ----

    suspend fun setNote(transactionId: String, note: String): Boolean {
        val nowMs = maxOf(clock().toEpochMilli(), lastNoteMs + 1)
        lastNoteMs = nowMs
        return sink.upsertTransactionNote(
            TransactionNote(
                id = UUID.randomUUID().toString(),
                transactionId = transactionId,
                note = note,
                sourceKind = SourceKind.USER_CORRECTION,
                sourceVersion = "notes-v1",
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs,
            ),
        )
    }

    suspend fun latestNote(transactionId: String): TransactionNote? =
        sink.latestNoteForTransaction(transactionId)

    // ---- reimbursement links ----

    /**
     * Link an expense to its reimbursing credit. Idempotent on the
     * (expense, reimbursing) pair — re-running is a no-op.
     */
    suspend fun linkReimbursement(
        expenseTransactionId: String,
        reimbursingTransactionId: String,
    ): Boolean {
        val identity = sha256("$expenseTransactionId|$reimbursingTransactionId")
        return sink.insertReimbursementLink(
            ReimbursementLink(
                id = UUID.randomUUID().toString(),
                expenseTransactionId = expenseTransactionId,
                reimbursingTransactionId = reimbursingTransactionId,
                linkIdentity = identity,
                sourceKind = SourceKind.USER_CORRECTION,
                sourceVersion = "reimb-v1",
                createdAtEpochMs = clock().toEpochMilli(),
            )
        )
    }

    suspend fun reimbursementsFor(expenseTransactionId: String): List<ReimbursementLink> =
        sink.reimbursementsForExpense(expenseTransactionId)

    // ---- travel mode ----

    /** Start a travel-mode window on an account. One active window per account. */
    suspend fun startTravelMode(
        accountId: com.example.fintrack.domain.model.EntityId,
        label: String,
        currencyCode: String,
        startEpochDay: Long,
    ): Boolean {
        val existing = sink.activeTravelModesFor(accountId.value)
        if (existing.isNotEmpty()) return false // one active window at a time
        return sink.insertTravelMode(
            TravelMode(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                label = label.trim(),
                currencyCode = currencyCode.uppercase(),
                startEpochDay = startEpochDay,
                endEpochDay = null,
                status = TravelModeStatus.ACTIVE,
                sourceKind = SourceKind.MANUAL_ENTRY,
                sourceVersion = "travel-v1",
                createdAtEpochMs = clock().toEpochMilli(),
            )
        )
    }

    /** End the active travel window. Returns false when none was active. */
    suspend fun endTravelMode(travelModeId: String, endEpochDay: Long): Boolean =
        sink.updateTravelModeStatus(travelModeId, TravelModeStatus.ENDED.name, endEpochDay)

    suspend fun activeTravelModesFor(accountId: String): List<TravelMode> =
        sink.activeTravelModesFor(accountId)

    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Persistence interface for [TagsNotesService]. */
interface TagsNotesSink {
    suspend fun insertTransactionTag(tag: TransactionTag): Boolean
    suspend fun deleteTransactionTag(transactionId: String, tag: String): Boolean
    suspend fun tagsForTransaction(transactionId: String): List<TransactionTag>
    suspend fun upsertTransactionNote(note: TransactionNote): Boolean
    suspend fun latestNoteForTransaction(transactionId: String): TransactionNote?
    suspend fun insertReimbursementLink(link: ReimbursementLink): Boolean
    suspend fun reimbursementsForExpense(expenseTransactionId: String): List<ReimbursementLink>
    suspend fun insertTravelMode(mode: TravelMode): Boolean
    suspend fun updateTravelModeStatus(id: String, status: String, endDay: Long?): Boolean
    suspend fun activeTravelModesFor(accountId: String): List<TravelMode>
}
