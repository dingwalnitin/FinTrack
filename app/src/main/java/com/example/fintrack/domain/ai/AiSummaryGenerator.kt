package com.example.fintrack.domain.ai

import com.example.fintrack.domain.service.LedgerTxnView
import com.example.fintrack.domain.model.TxKind

/**
 * Stage 10 / P21 — AI financial summaries built ONLY from retrieved
 * structured facts.
 *
 * The summary generator never sees raw SMS, never invents numbers, and every
 * claim it emits carries a citation to the plan result (aggregate row key or
 * transaction id) so the UI can ground each statement. When coverage is
 * incomplete the summary is explicitly qualified — incomplete history yields
 * qualified language, not false certainty.
 *
 * This class produces a deterministic draft from structured facts. An
 * optional LLM may REWRITE the draft, but the rewrite must pass through
 * [SummaryGuardrails] which verifies every claim still cites an existing
 * result id and no unsupported number appears.
 */
class AiSummaryGenerator {

    data class SummaryClaim(
        /** Human-readable sentence grounded in the cited fact. */
        val text: String,
        /** Citation: aggregate group key or transaction id. Never fabricated. */
        val citation: Citation,
    ) {
        sealed interface Citation {
            data class Aggregate(val dimension: AiQueryPlan.Dimension?, val key: String?) : Citation
            data class Transaction(val transactionId: String) : Citation
            data object Coverage : Citation
        }
    }

    data class Summary(
        val claims: List<SummaryClaim>,
        val coverage: Coverage,
        val planIdentity: String,
    ) {
        /** True when any qualification applies — UI must show the caveat. */
        val isQualified: Boolean
            get() = !coverage.hasData || coverage.ingestionIncomplete ||
                coverage.unknownKindCount > 0 || coverage.windowExtendsBeforeHistory ||
                coverage.uncategorizedShare > 0.5

        fun qualifications(): List<String> = buildList {
            if (!coverage.hasData) add("No transactions found in this period.")
            if (coverage.ingestionIncomplete) {
                add("SMS history is still being imported; totals cover only messages received so far.")
            }
            if (coverage.windowExtendsBeforeHistory) {
                add("The requested period starts before your earliest recorded transaction.")
            }
            if (coverage.unknownKindCount > 0) {
                add("${coverage.unknownKindCount} transactions have unrecognized type and are excluded from spend/income totals.")
            }
            if (coverage.uncategorizedShare > 0.5) {
                add("Most transactions are uncategorized; category breakdowns are partial.")
            }
        }
    }

    fun summarize(
        result: PlanResult,
        plan: AiQueryPlan,
        categoryLabels: Map<String, String> = emptyMap(),
        accountLabels: Map<String, String> = emptyMap(),
    ): Summary {
        val claims = mutableListOf<SummaryClaim>()

        // Coverage-first: lead with what the data does and does not cover.
        claims += SummaryClaim(
            text = when {
                !result.coverage.hasData -> "No matching transactions were found."
                else -> "Based on ${result.totalMatching} recorded transactions" +
                    " (${result.coverage.firstObservedDay?.let { "from day $it" } ?: ""}" +
                    "${result.coverage.lastObservedDay?.let { " to day $it" } ?: ""})."
            },
            citation = SummaryClaim.Citation.Coverage,
        )

        // Aggregate claims cite their group key.
        result.aggregates.take(10).forEach { agg ->
            val label = labelFor(agg, categoryLabels, accountLabels)
            claims += SummaryClaim(
                text = "$label: net ${agg.netMinor} minor (${agg.grossMinor} gross − ${agg.refundedMinor} refunded) across ${agg.count} transactions.",
                citation = SummaryClaim.Citation.Aggregate(agg.dimension, agg.key),
            )
        }

        // Top transaction rows cite their ids.
        result.rows.take(5).forEach { row ->
            claims += SummaryClaim(
                text = "${row.merchant ?: row.counterpartyNormalized ?: "Unknown payee"}: ${row.amountMinor} minor on day ${row.localDateEpochDay}.",
                citation = SummaryClaim.Citation.Transaction(row.transactionId),
            )
        }

        return Summary(
            claims = claims,
            coverage = result.coverage,
            planIdentity = plan.planIdentity,
        )
    }

    private fun labelFor(
        agg: PlanResult.AggregateRow,
        categoryLabels: Map<String, String>,
        accountLabels: Map<String, String>,
    ): String {
        val base = when (agg.dimension) {
            AiQueryPlan.Dimension.CATEGORY ->
                agg.key?.let { categoryLabels[it] ?: it } ?: "Uncategorized"
            AiQueryPlan.Dimension.ACCOUNT ->
                agg.key?.let { accountLabels[it] ?: it } ?: "All accounts"
            AiQueryPlan.Dimension.MERCHANT -> agg.key ?: "Unknown merchant"
            AiQueryPlan.Dimension.RAIL -> agg.key?.let { "Rail $it" } ?: "Unknown rail"
            AiQueryPlan.Dimension.DAY -> agg.key?.let { "Day $it" } ?: "All days"
            AiQueryPlan.Dimension.MONTH -> agg.key?.let { "Month $it" } ?: "All months"
            null -> "Overall"
        }
        return base
    }
}
