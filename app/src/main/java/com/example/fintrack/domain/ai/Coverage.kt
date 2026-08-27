package com.example.fintrack.domain.ai

import com.example.fintrack.domain.service.LedgerTxnView

/**
 * Stage 10 / P21 — coverage indicators for AI summaries.
 *
 * Incomplete SMS history must produce a QUALIFIED summary, never false
 * certainty. Coverage is computed from the ledger snapshot itself (first /
 * last observed event, unknown-kind share) plus caller-supplied ingestion
 * facts. Every flag is data, not a guess.
 */
data class Coverage(
    /** True when the caller knows SMS ingestion is still partial/backfilling. */
    val ingestionIncomplete: Boolean,
    /** Epoch day of the earliest active transaction in scope; null = no data. */
    val firstObservedDay: Long?,
    /** Epoch day of the latest active transaction in scope; null = no data. */
    val lastObservedDay: Long?,
    /** Count of rows whose economic meaning is UNKNOWN — visible uncertainty. */
    val unknownKindCount: Int,
    /** Share of in-scope rows with no category (0..1); 1.0 when no rows. */
    val uncategorizedShare: Double,
    /** True when the requested window extends before the first observation. */
    val windowExtendsBeforeHistory: Boolean,
) {
    val hasData: Boolean get() = firstObservedDay != null

    companion object {
        val EMPTY = Coverage(
            ingestionIncomplete = true,
            firstObservedDay = null,
            lastObservedDay = null,
            unknownKindCount = 0,
            uncategorizedShare = 1.0,
            windowExtendsBeforeHistory = false,
        )

        fun of(
            txns: List<LedgerTxnView>,
            ingestionIncomplete: Boolean = false,
            windowFromDay: Long? = null,
        ): Coverage {
            if (txns.isEmpty()) return EMPTY.copy(ingestionIncomplete = ingestionIncomplete)
            val days = txns.map { it.localDateEpochDay }
            val uncategorized = txns.count { it.categoryId.isNullOrBlank() }
            return Coverage(
                ingestionIncomplete = ingestionIncomplete,
                firstObservedDay = days.min(),
                lastObservedDay = days.max(),
                unknownKindCount = txns.count { it.kind == "UNKNOWN" },
                uncategorizedShare = uncategorized.toDouble() / txns.size,
                windowExtendsBeforeHistory = windowFromDay != null &&
                    windowFromDay < days.min(),
            )
        }
    }
}

