package com.example.fintrack.domain.ai

import com.example.fintrack.domain.model.TxKind
import java.time.LocalDate

/**
 * Stage 10 / P21 — typed, validated intermediate query plan (module 170).
 *
 * The plan is the ONLY thing an LLM or NL parser may produce. It is a pure
 * data structure with no SQL, no Room references and no free-form model
 * output. Execution happens in the domain engine over [LedgerTxnView] rows
 * fetched by the data layer — never by handing a model direct database
 * access.
 *
 * Validation rules:
 *  - [limit] is bounded (1..500) so no query can dump the whole ledger.
 *  - [fromDay] <= [toDay] when both present.
 *  - [metrics] must be non-empty for AGGREGATE queries; LIST requires none.
 *  - [groupBy] dimensions must be distinct and drawn from the supported set.
 *  - Unknown enum values are rejected at parse time, not silently dropped.
 */
data class AiQueryPlan(
    val intent: Intent,
    val metrics: Set<Metric> = emptySet(),
    val groupBy: List<Dimension> = emptyList(),
    val filters: Filters = Filters(),
    val sort: Sort = Sort(),
    val limit: Int = DEFAULT_LIMIT,
    /** Deterministic identity of the plan content (sha-256 of canonical form). */
    val planIdentity: String,
    val parsedAtEpochMs: Long,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit $limit outside 1..$MAX_LIMIT" }
        require(filters.fromDay == null || filters.toDay == null || filters.fromDay <= filters.toDay) {
            "fromDay after toDay"
        }
        if (intent == Intent.AGGREGATE) {
            require(metrics.isNotEmpty()) { "AGGREGATE requires at least one metric" }
        }
        require(groupBy.size == groupBy.distinct().size) { "duplicate groupBy dimension" }
    }

    enum class Intent { LIST_TRANSACTIONS, AGGREGATE }

    enum class Metric {
        TOTAL_SPEND, TOTAL_INCOME, NET_FLOW, TRANSACTION_COUNT,
        SPEND_BY_CATEGORY, SPEND_BY_MERCHANT, SPEND_BY_ACCOUNT, SPEND_BY_RAIL,
    }

    enum class Dimension { CATEGORY, MERCHANT, ACCOUNT, RAIL, DAY, MONTH }

    /**
     * Explicit filters. Every field is optional; absent means unfiltered.
     * Account/category ids are stable UUIDs resolved from user language via
     * deterministic aliasing — never model-invented identifiers.
     */
    data class Filters(
        val fromDay: Long? = null,
        val toDay: Long? = null,
        val accountIds: Set<String>? = null,
        val categoryIds: Set<String>? = null,
        val kinds: Set<TxKind>? = null,
        val merchantNormalized: String? = null,
        val rails: Set<String>? = null,
        val minAmountMinor: Long? = null,
        val maxAmountMinor: Long? = null,
    )

    data class Sort(
        val field: SortField = SortField.OCCURRED_AT,
        val direction: SortDirection = SortDirection.DESC,
    ) {
        enum class SortField { OCCURRED_AT, AMOUNT }
        enum class SortDirection { ASC, DESC }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}

/**
 * Result of executing a validated plan. Deterministic given the same ledger
 * snapshot: rows are sorted with an id tiebreak so pagination is stable.
 */
data class PlanResult(
    val planIdentity: String,
    val executedAtEpochMs: Long,
    /** For LIST intents: bounded page of matching transactions. */
    val rows: List<PlanRow>,
    /** For AGGREGATE intents: one row per group key (null key = uncategorized/unknown). */
    val aggregates: List<AggregateRow>,
    val totalMatching: Int,
    val hasMore: Boolean,
    /** Coverage flags so incomplete SMS history produces qualified summaries. */
    val coverage: Coverage,
) {
    data class PlanRow(
        val transactionId: String,
        val accountId: String,
        val categoryId: String?,
        val kind: String,
        val directionDebit: Boolean,
        val amountMinor: Long,
        val currencyCode: String,
        val occurredAtEpochMs: Long,
        val localDateEpochDay: Long,
        val merchant: String?,
        val counterpartyNormalized: String?,
        val rail: String?,
        val subtype: String?,
        val userCorrected: Boolean,
    )

    data class AggregateRow(
        val dimension: AiQueryPlan.Dimension?,
        /** Group key: category id / normalized merchant / account id / rail / epoch-day. Null = uncategorized/unknown bucket. */
        val key: String?,
        val grossMinor: Long,
        val refundedMinor: Long,
        val netMinor: Long,
        val count: Int,
        val currencyCode: String,
    )
}
