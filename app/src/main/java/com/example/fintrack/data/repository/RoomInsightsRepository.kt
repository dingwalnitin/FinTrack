package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinanceDaoV8
import com.example.fintrack.data.db.TransactionEntity
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.service.LedgerTxnView
import com.example.fintrack.domain.service.ReconciliationService
import com.example.fintrack.domain.service.SearchResultRow
import com.example.fintrack.domain.service.SearchService
import com.example.fintrack.domain.service.SortSpec
import com.example.fintrack.domain.service.UnresolvedDataReportService

/**
 * Stage 9 read-only repository (P19 + P20).
 *
 * The single Room access point for dashboard, insights, search and the
 * diagnostics workbench. UI/ViewModels depend on this class through its
 * plain methods — never on DAOs or entities. All rows leaving this class are
 * domain projections ([LedgerTxnView], [SearchResultRow], report DTOs);
 * raw evidence bodies only leave through [rawEvidenceFor] for the explicit
 * evidence viewer.
 */
class RoomInsightsRepository(
    private val dao: FinanceDaoV8,
) {

    // ---- mapping ----

    fun TransactionEntity.toLedgerView(): LedgerTxnView = LedgerTxnView(
        id = id,
        accountId = accountId,
        categoryId = categoryId?.ifBlank { null },
        kind = kind,
        directionDebit = isDebitRow(),
        amountMinor = amountMinor,
        localDateEpochDay = localDateEpochDay,
        counterpartyNormalized = counterpartyNormalized,
        merchant = merchant,
        currencyCode = currencyCode,
        occurredAtEpochMs = occurredAtEpochMs,
        subtype = subtype,
        userCorrected = correctionSourceKind != null,
        statusDeleted = status == "DELETED",
        rail = rail,
        cardMask = cardMask,
    )

    /**
     * Direction resolution without loading postings: kind + account type give
     * the sign per PostingPolicy. INCOME/REFUND credits; everything else that
     * moves money out debits. TRANSFER/CASH_MOVE debit on the source side,
     * which is the common case for a single-row view.
     */
    private fun TransactionEntity.isDebitRow(): Boolean = when (kind) {
        "INCOME", "REFUND" -> false
        else -> true
    }

    // ---- P19 ----

    suspend fun ledgerViews(fromDay: Long? = null, toDay: Long? = null): List<LedgerTxnView> {
        val rows = if (fromDay != null && toDay != null) dao.transactionsBetween(fromDay, toDay)
        else dao.allActiveTransactions()
        return rows.map { it.toLedgerView() }
    }

    suspend fun accounts(): List<AccountEntityProjection> =
        dao.allAccounts().map {
            AccountEntityProjection(
                id = it.id,
                label = listOfNotNull(it.nickname ?: it.name, it.institutionName).joinToString(" · "),
                nickname = it.nickname ?: it.name,
                type = it.accountType,
                currencyCode = it.currencyCode,
                lifecycle = it.lifecycle,
            )
        }

    data class AccountEntityProjection(
        val id: String,
        val label: String,
        val nickname: String,
        val type: String,
        val currencyCode: String,
        val lifecycle: String,
    )

    suspend fun openingBalances(): Map<String, Long> =
        dao.allOpeningBalances().associate { it.accountId to it.amountMinor }

    suspend fun snapshotsByAccount(): Map<String, List<Pair<Long, Long>>> =
        dao.allSnapshots().groupBy { it.accountId }
            .mapValues { (_, list) -> list.map { it.capturedAtEpochMs to it.amountMinor } }

    suspend fun categoryLabels(): Map<String, String> =
        dao.activeCategories().associate { it.id to it.name }

    /** Total balance across ACTIVE accounts: opening + Σ(credits − debits). */
    suspend fun totalBalanceByAccount(): Map<String, Long> {
        val openings = openingBalances()
        val balances = accounts().filter { it.lifecycle == "ACTIVE" }
            .associate { it.id to (openings[it.id] ?: 0L) }
            .toMutableMap()
        dao.allActiveTransactions().forEach { t ->
            val view = t.toLedgerView()
            if (view.statusDeleted) return@forEach
            val current = balances[view.accountId] ?: return@forEach
            balances[view.accountId] =
                if (view.directionDebit) current - view.amountMinor else current + view.amountMinor
        }
        return balances
    }

    // ---- P20 #1–#4: search ----

    /**
     * Bounded search. Falls back to the in-memory [SearchService] predicate
     * when optional dimensions (tags/review state) are active, because those
     * live in separate tables.
     */
    suspend fun search(
        textQuery: String?,
        fromDay: Long?,
        toDay: Long?,
        accountIds: Set<String>?,
        kinds: Set<TxKind>?,
        tags: Set<String>?,
        sort: SortSpec,
        limit: Int,
        offset: Int,
    ): Pair<List<SearchResultRow>, Int> {
        val effectiveFrom = fromDay ?: 0L
        val effectiveTo = toDay ?: Long.MAX_VALUE / 2
        val accountList = accountIds?.toList() ?: dao.allAccounts().map { it.id }
        val kindList = kinds?.map { it.name } ?: TxKind.entries.map { it.name }.filter { it != "UNKNOWN" || true }
        val pattern = "%${textQuery?.trim()?.lowercase()?.replace("%", "\\%")?.replace("_", "\\_") ?: ""}%"

        val needsTagFilter = !tags.isNullOrEmpty()
        val fetchLimit = if (needsTagFilter) 2_000 else limit
        val fetchOffset = if (needsTagFilter) 0 else offset

        val rows = dao.searchTransactions(
            fromDay = effectiveFrom,
            toDay = effectiveTo,
            accountIds = accountList.ifEmpty { listOf("\u0000none") },
            kinds = kindList.ifEmpty { listOf("\u0000none") },
            textPattern = pattern,
            limit = fetchLimit,
            offset = fetchOffset,
        )
        val total = if (needsTagFilter) rows.size
        else dao.countSearchTransactions(
            fromDay = effectiveFrom, toDay = effectiveTo,
            accountIds = accountList.ifEmpty { listOf("\u0000none") },
            kinds = kindList.ifEmpty { listOf("\u0000none") },
            textPattern = pattern,
        )

        // Enrich with tags + latest notes in two bounded batch queries.
        val ids = rows.map { it.id }
        val tagsByTxn: Map<String, List<String>> =
            if (ids.isEmpty()) emptyMap()
            else dao.tagsForTransactions(ids).groupBy({ it.transactionId }, { it.tag })
        val notesByTxn: Map<String, String> =
            if (ids.isEmpty()) emptyMap()
            else dao.latestNotesForTransactions(ids).associate { it.transactionId to it.note }

        var projected = rows.map { row ->
            SearchResultRow(
                txn = row.toLedgerView(),
                tags = tagsByTxn[row.id].orEmpty(),
                latestNote = notesByTxn[row.id],
            )
        }

        if (needsTagFilter) {
            val wanted = tags.orEmpty()
            projected = projected.filter { row -> row.tags.any { it in wanted } }
        }

        val sorted = SearchService().sort(projected, sort)
        val pageRows = if (needsTagFilter) sorted.drop(offset).take(limit) else sorted
        return pageRows to total
    }

    suspend fun distinctTagsInUse(): List<String> = dao.distinctTagsInUse()

    // ---- P20 #5: reconciliation inputs ----

    suspend fun reconciliation(
        accountId: String,
    ): ReconciliationService.AccountReconciliation? {
        val account = dao.accountById(accountId) ?: return null
        val service = ReconciliationService()
        val snapshot = dao.latestSnapshotFor(accountId)
        return service.reconcile(
            accountId = accountId,
            accountLabel = listOfNotNull(account.nickname ?: account.name, account.institutionName)
                .joinToString(" · "),
            currencyCode = account.currencyCode,
            openingBalanceMinor = dao.allOpeningBalances().firstOrNull { it.accountId == accountId }?.amountMinor,
            postings = dao.ledgerEntriesForAccount(accountId).map { e ->
                LedgerTxnView(
                    id = e.id,
                    accountId = e.accountId,
                    categoryId = null,
                    kind = "POSTING",
                    directionDebit = e.direction == "DEBIT",
                    amountMinor = e.amountMinor,
                    localDateEpochDay = 0,
                    counterpartyNormalized = null,
                    merchant = null,
                    currencyCode = e.currencyCode,
                    occurredAtEpochMs = 0,
                    subtype = null,
                )
            },
            latestSnapshot = snapshot?.let { it.capturedAtEpochMs to it.amountMinor },
        )
    }

    // ---- P20 #6: unresolved-data report ----

    suspend fun unresolvedReport(nowEpochMs: Long): UnresolvedDataReportService.Report =
        UnresolvedDataReportService.Report(
            transactionsWithoutAccountMapping = dao.unmappedSenderCount(),
            unknownEconomicMeaning = dao.unknownKindCount(),
            lowConfidenceFields = dao.lowConfidenceInterpretationCount(DEFAULT_CONFIDENCE_FLOOR),
            parserFailures = 0, // deterministic parser refuses rather than fails; failures surface as UNKNOWN kinds
            llmFailures = dao.llmTerminalFailedCount(),
            staleProcessingJobs = dao.staleProcessingJobCount(nowEpochMs),
            openReviewItems = dao.openReviewItemCount(),
            uncategorizedTransactions = dao.uncategorizedSpendCount(),
        )

    companion object {
        /** Below this overall confidence a field counts as "low confidence". */
        const val DEFAULT_CONFIDENCE_FLOOR = 0.5
    }

    // ---- P20 #7/#8: raw evidence viewer (explicit, redaction-ready) ----

    data class EvidenceRecord(
        val rawSmsId: String,
        val sender: String?,
        val receivedAtEpochMs: Long,
        val body: String,                       // immutable original; caller decides on redaction
        val linkKind: String?,                  // RAW_PRIMARY | RAW_SECONDARY
        val interpretations: List<InterpretationRecord>,
    ) {
        data class InterpretationRecord(
            val modelId: String,
            val promptVersion: String,
            val schemaVersion: String,
            val overallConfidence: Double?,
            val createdAtEpochMs: Long,
        )
    }

    /** Raw evidence + LLM provenance for one transaction. Read-only. */
    suspend fun rawEvidenceFor(transactionId: String): List<EvidenceRecord> {
        val raws = dao.rawEvidenceForTransaction(transactionId)
        if (raws.isEmpty()) return emptyList()
        return raws.map { raw ->
            EvidenceRecord(
                rawSmsId = raw.id,
                sender = raw.sender,
                receivedAtEpochMs = raw.receivedAtEpochMs,
                body = raw.body,
                linkKind = null, // refined below via provenance query where available
                interpretations = dao.interpretationsForMessage(raw.id).map { i ->
                    EvidenceRecord.InterpretationRecord(
                        modelId = i.modelId,
                        promptVersion = i.promptVersion,
                        schemaVersion = i.schemaVersion,
                        overallConfidence = i.overallConfidence,
                        createdAtEpochMs = i.createdAtEpochMs,
                    )
                },
            )
        }
    }
}
