package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.Transaction

/**
 * Stage 13 (B) — pure, JVM-testable filter/sort logic for the Transactions
 * page. Kept free of Compose/Room so it runs deterministically in unit tests.
 *
 * Filtering is always driven by [Transaction.directionDebit] for the
 * INCOME/EXPENSE axis — never by the sign of `amountMinor`, which is always
 * absolute. The sign of a row's money is a presentation concern resolved at
 * the UI layer from `directionDebit`.
 */
object TransactionFilter {

    enum class KindFilter { ALL, INCOME, EXPENSE }
    enum class SortField { DATE, AMOUNT, MERCHANT }
    enum class SortOrder { ASC, DESC }

    data class SortSpec(
        val field: SortField = SortField.DATE,
        val order: SortOrder = SortOrder.DESC,
    )

    data class Filters(
        val kind: KindFilter = KindFilter.ALL,
        val query: String = "",
        val categoryId: String? = null,
        val rail: String? = null,
        val accountId: String? = null,
        val minAmountMinor: Long? = null,
        val maxAmountMinor: Long? = null,
        val dateFromDay: Long? = null,
        val dateToDay: Long? = null,
    )

    /** True when the transaction survives every active filter. */
    fun matches(txn: Transaction, f: Filters): Boolean {
        if (!f.query.isBlank()) {
            val q = f.query.trim()
            val haystack = listOfNotNull(
                txn.counterparty,
            ).joinToString(" ").lowercase()
            if (!haystack.contains(q.lowercase())) return false
        }
        val kindOk = when (f.kind) {
            KindFilter.ALL -> true
            KindFilter.INCOME -> !txn.directionDebit
            KindFilter.EXPENSE -> txn.directionDebit
        }
        if (!kindOk) return false
        if (f.accountId != null && f.accountId != txn.accountId) return false
        if (f.categoryId != null && f.categoryId != txn.categoryId) return false
        if (f.rail != null && f.rail != txn.rail) return false
        if (f.minAmountMinor != null && txn.amount.minorUnits < f.minAmountMinor) return false
        if (f.maxAmountMinor != null && txn.amount.minorUnits > f.maxAmountMinor) return false
        return true
    }

    fun compare(a: Transaction, b: Transaction, spec: SortSpec): Int {
        val base = when (spec.field) {
            SortField.DATE -> a.occurredAt.compareTo(b.occurredAt)
            SortField.AMOUNT -> a.amount.minorUnits.compareTo(b.amount.minorUnits)
            SortField.MERCHANT -> (a.counterparty ?: "").compareTo(b.counterparty ?: "")
        }
        return if (spec.order == SortOrder.ASC) base else -base
    }

    /** Apply filters then sort; returns a new list. */
    fun apply(txns: List<Transaction>, filters: Filters, sort: SortSpec): List<Transaction> =
        txns.filter { matches(it, filters) }.sortedWith { a, b -> compare(a, b, sort) }
}
