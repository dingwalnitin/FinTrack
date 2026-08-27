package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.TxKind

/**
 * Stage 9 P20 #1/#2/#3 — authoritative transaction discovery.
 *
 * Pure filtering/sorting/pagination over ledger read rows. The data layer
 * applies the same predicate in SQL for large datasets; this engine is the
 * single definition of what a filter means (no parallel truth).
 */
data class SearchFilter(
    /** Free-text query matched against merchant, counterparty, description, reference. */
    val textQuery: String? = null,
    val fromDay: Long? = null,
    val toDay: Long? = null,
    val accountIds: Set<String>? = null,
    val kinds: Set<TxKind>? = null,
    val statuses: Set<String>? = null,          // TxStatus names; null = all non-deleted
    val categoryIds: Set<String>? = null,
    /** null = any; empty set + includeUncategorized=true = only uncategorized. */
    val merchantNormalized: String? = null,
    val rails: Set<String>? = null,
    val tags: Set<String>? = null,
    val reviewStates: Set<String>? = null,      // OPEN | RESOLVED | DISMISSED
    val includeUncategorizedOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = this == SearchFilter()

    companion object {
        val NONE = SearchFilter()
    }
}

enum class SortField { OCCURRED_AT, AMOUNT, MERCHANT }
enum class SortDirection { ASC, DESC }

data class SortSpec(
    val field: SortField = SortField.OCCURRED_AT,
    val direction: SortDirection = SortDirection.DESC,
)

data class PageRequest(val offset: Int, val limit: Int) {
    init {
        require(offset >= 0)
        require(limit in 1..500)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
        fun first() = PageRequest(0, DEFAULT_PAGE_SIZE)
    }
}

data class SearchResultRow(
    val txn: LedgerTxnView,
    val tags: List<String> = emptyList(),
    val latestNote: String? = null,
    val hasOpenReviewItems: Boolean = false,
)

data class SearchResult(
    val rows: List<SearchResultRow>,
    val totalMatching: Int,
    val page: PageRequest,
    val hasMore: Boolean,
)

class SearchService {

    /**
     * Deterministic text matcher used by both the SQL LIKE path and the
     * in-memory path so results agree regardless of dataset size.
     */
    fun matchesText(row: LedgerTxnView, note: String?, tags: List<String>, query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val q = query.trim().lowercase()
        val haystacks = listOfNotNull(
            row.merchant,
            row.counterpartyNormalized,
            row.rail,
            row.subtype,
            note,
        ) + tags
        return haystacks.any { it?.lowercase()?.contains(q) == true }
    }

    fun matchesFilter(
        row: LedgerTxnView,
        filter: SearchFilter,
        tags: List<String> = emptyList(),
        hasOpenReviewItems: Boolean = false,
    ): Boolean {
        if (row.statusDeleted && filter.statuses.isNullOrEmpty()) return false
        if (filter.statuses != null) {
            // Status filtering is done at the data layer on the status column;
            // here we can only honor the deleted dimension.
            if (row.statusDeleted && "DELETED" !in filter.statuses) return false
        }
        if (filter.fromDay != null && row.localDateEpochDay < filter.fromDay) return false
        if (filter.toDay != null && row.localDateEpochDay > filter.toDay) return false
        if (filter.accountIds != null && row.accountId !in filter.accountIds) return false
        if (filter.kinds != null && row.kind !in filter.kinds.map { it.name }) return false
        if (filter.categoryIds != null) {
            val cat = row.categoryId?.ifBlank { null }
            if (cat == null || cat !in filter.categoryIds) return false
        }
        if (filter.includeUncategorizedOnly && row.categoryId?.ifBlank { null } != null) return false
        if (filter.merchantNormalized != null &&
            row.counterpartyNormalized?.contains(filter.merchantNormalized.lowercase()) != true &&
            row.merchant?.lowercase()?.contains(filter.merchantNormalized.lowercase()) != true
        ) return false
        if (filter.rails != null && (row.rail == null || row.rail!!.uppercase() !in filter.rails)) return false
        if (filter.tags != null && filter.tags.isNotEmpty() && tags.none { it in filter.tags!! }) return false
        if (filter.reviewStates != null) {
            val wanted = "OPEN" in filter.reviewStates
            if (hasOpenReviewItems != wanted) return false
        }
        return true
    }

    fun sort(rows: List<SearchResultRow>, spec: SortSpec): List<SearchResultRow> {
        val primary: Comparator<SearchResultRow> = when (spec.field) {
            SortField.OCCURRED_AT -> compareBy { it.txn.occurredAtEpochMs }
            SortField.AMOUNT -> compareBy { it.txn.amountMinor }
            SortField.MERCHANT -> compareBy {
                it.txn.merchant ?: it.txn.counterpartyNormalized ?: "\uFFFF"
            }
        }
        // Primary sort in the requested direction; the id tiebreaker always
        // runs ascending so pagination stays deterministic across pages.
        val comparator = if (spec.direction == SortDirection.DESC) {
            primary.reversed().thenComparator { a, b -> a.txn.id.compareTo(b.txn.id) }
        } else {
            primary.thenComparator { a, b -> a.txn.id.compareTo(b.txn.id) }
        }
        return rows.sortedWith(comparator)
    }

    fun paginate(rows: List<SearchResultRow>, page: PageRequest): SearchResult =
        SearchResult(
            rows = rows.drop(page.offset).take(page.limit),
            totalMatching = rows.size,
            page = page,
            hasMore = page.offset + page.limit < rows.size,
        )

    fun search(
        rows: List<SearchResultRow>,
        filter: SearchFilter,
        sort: SortSpec = SortSpec(),
        page: PageRequest = PageRequest.first(),
    ): SearchResult {
        val filtered = rows.filter {
            matchesFilter(it.txn, filter, it.tags, it.hasOpenReviewItems) &&
                matchesText(it.txn, it.latestNote, it.tags, filter.textQuery)
        }
        return paginate(sort(filtered, sort), page)
    }
}
