package com.example.fintrack.domain.ai

import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.service.LedgerTxnView
import java.security.MessageDigest

/**
 * Stage 10 / P21 — deterministic plan executor over ledger read rows.
 *
 * Pure domain logic: takes a validated [AiQueryPlan] plus the ledger snapshot
 * the data layer fetched, and produces a deterministic, bounded [PlanResult].
 * No Room, no SQL, no network. The same (plan, snapshot) always yields the
 * same result.
 *
 * Rules inherited from InsightsEngine:
 *  - TRANSFER / CASH_MOVE rows are internal movements and never count as
 *    spend or income unless explicitly requested via kinds filter.
 *  - Refunds reduce spend (gross vs net is explicit on every aggregate).
 *  - Uncategorized / unknown buckets surface as a null key rather than being
 *    hidden or guessed into a category.
 */
class AiQueryEngine {

    fun execute(
        plan: AiQueryPlan,
        txns: List<LedgerTxnView>,
    ): PlanResult {
        val filtered = txns.filter { matches(it, plan.filters) }
        val sorted = sort(filtered, plan)
        val page = sorted.take(plan.limit)

        val aggregates = if (plan.intent == AiQueryPlan.Intent.AGGREGATE) {
            aggregate(page, plan)
        } else {
            emptyList()
        }

        return PlanResult(
            planIdentity = plan.planIdentity,
            executedAtEpochMs = System.currentTimeMillis(),
            rows = page.map { it.toPlanRow() },
            aggregates = aggregates,
            totalMatching = filtered.size,
            hasMore = filtered.size > page.size,
            coverage = Coverage.of(
                filtered,
                windowFromDay = plan.filters.fromDay,
            ),
        )
    }

    // ---- filtering ----

    private fun matches(t: LedgerTxnView, f: AiQueryPlan.Filters): Boolean {
        if (t.statusDeleted) return false
        if (f.fromDay != null && t.localDateEpochDay < f.fromDay) return false
        if (f.toDay != null && t.localDateEpochDay > f.toDay) return false
        if (f.accountIds != null && t.accountId !in f.accountIds) return false
        if (f.categoryIds != null) {
            val cat = t.categoryId?.ifBlank { null }
            if (cat == null || cat !in f.categoryIds) return false
        }
        if (f.kinds != null && t.kind !in f.kinds.map { it.name }) return false
        if (f.merchantNormalized != null) {
            val needle = f.merchantNormalized.lowercase()
            val hit = t.merchant?.lowercase()?.contains(needle) == true ||
                t.counterpartyNormalized?.contains(needle) == true
            if (!hit) return false
        }
        if (f.rails != null && (t.rail == null || t.rail!!.uppercase() !in f.rails)) return false
        if (f.minAmountMinor != null && t.amountMinor < f.minAmountMinor) return false
        if (f.maxAmountMinor != null && t.amountMinor > f.maxAmountMinor) return false
        return true
    }

    // ---- deterministic ordering with id tiebreak ----

    private fun sort(rows: List<LedgerTxnView>, plan: AiQueryPlan): List<LedgerTxnView> {
        val primary = when (plan.sort.field) {
            AiQueryPlan.Sort.SortField.OCCURRED_AT -> compareBy<LedgerTxnView> { it.occurredAtEpochMs }
            AiQueryPlan.Sort.SortField.AMOUNT -> compareBy<LedgerTxnView> { it.amountMinor }
        }
        val comparator = if (plan.sort.direction == AiQueryPlan.Sort.SortDirection.DESC) {
            primary.reversed().thenComparator { a, b -> a.id.compareTo(b.id) }
        } else {
            primary.thenComparator { a, b -> a.id.compareTo(b.id) }
        }
        return rows.sortedWith(comparator)
    }

    // ---- aggregation ----

    private fun aggregate(rows: List<LedgerTxnView>, plan: AiQueryPlan): List<PlanResult.AggregateRow> {
        if (plan.groupBy.isEmpty()) {
            var gross = 0L
            var refunded = 0L
            var count = 0
            var currency = "INR"
            rows.forEach { t ->
                currency = t.currencyCode
                when {
                    isRefundCredit(t) -> refunded += t.amountMinor
                    isExternalSpend(t) -> gross += t.amountMinor
                    // INCOME rows contribute to count only; spend metrics stay spend-only.
                }
                count++
            }
            return listOf(
                PlanResult.AggregateRow(
                    dimension = null,
                    key = null,
                    grossMinor = gross,
                    refundedMinor = refunded,
                    netMinor = gross - refunded,
                    count = count,
                    currencyCode = currency,
                ),
            )
        }

        class Acc(var gross: Long = 0, var refunded: Long = 0, var count: Int = 0, var currency: String = "INR")

        val grouped = linkedMapOf<Pair<AiQueryPlan.Dimension, String?>, Acc>()
        rows.forEach { t ->
            plan.groupBy.forEach { dim ->
                val key = keyFor(t, dim)
                val acc = grouped.getOrPut(dim to key) { Acc(currency = t.currencyCode) }
                when {
                    isRefundCredit(t) -> acc.refunded += t.amountMinor
                    isExternalSpend(t) -> acc.gross += t.amountMinor
                }
                acc.count++
            }
        }
        return grouped.map { (k, acc) ->
            PlanResult.AggregateRow(
                dimension = k.first,
                key = k.second,
                grossMinor = acc.gross,
                refundedMinor = acc.refunded,
                netMinor = acc.gross - acc.refunded,
                count = acc.count,
                currencyCode = acc.currency,
            )
        }.sortedWith(
            compareByDescending<PlanResult.AggregateRow> { it.netMinor }
                .thenComparator { a, b -> (a.key ?: "").compareTo(b.key ?: "") },
        )
    }

    private fun keyFor(t: LedgerTxnView, dim: AiQueryPlan.Dimension): String? = when (dim) {
        AiQueryPlan.Dimension.CATEGORY -> t.categoryId?.ifBlank { null }
        AiQueryPlan.Dimension.MERCHANT -> t.merchant ?: t.counterpartyNormalized
        AiQueryPlan.Dimension.ACCOUNT -> t.accountId
        AiQueryPlan.Dimension.RAIL -> t.rail
        AiQueryPlan.Dimension.DAY -> t.localDateEpochDay.toString()
        AiQueryPlan.Dimension.MONTH -> monthKey(t.localDateEpochDay)
    }

    /** Derives YYYY-MM from an epoch-day without wall-clock strings. */
    private fun monthKey(epochDay: Long): String {
        val date = java.time.LocalDate.ofEpochDay(epochDay)
        return "%04d-%02d".format(date.year, date.monthValue)
    }

    // ---- classification (mirrors InsightsEngine rules exactly) ----

    private fun isExternalSpend(t: LedgerTxnView): Boolean =
        !t.statusDeleted && t.directionDebit &&
            (t.kind == TxKind.EXPENSE.name || t.kind == TxKind.FEE.name)

    private fun isRefundCredit(t: LedgerTxnView): Boolean =
        !t.statusDeleted && !t.directionDebit && t.kind == TxKind.REFUND.name

    private fun LedgerTxnView.toPlanRow() = PlanResult.PlanRow(
        transactionId = id,
        accountId = accountId,
        categoryId = categoryId?.ifBlank { null },
        kind = kind,
        directionDebit = directionDebit,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        occurredAtEpochMs = occurredAtEpochMs,
        localDateEpochDay = localDateEpochDay,
        merchant = merchant,
        counterpartyNormalized = counterpartyNormalized,
        rail = rail,
        subtype = subtype,
        userCorrected = userCorrected,
    )

    companion object {
        fun sha256(raw: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
