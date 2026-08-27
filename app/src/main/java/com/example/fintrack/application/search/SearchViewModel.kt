package com.example.fintrack.application.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.data.repository.RoomInsightsRepository
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.service.RedactionEngine
import com.example.fintrack.domain.service.ReconciliationService
import com.example.fintrack.domain.service.SearchResultRow
import com.example.fintrack.domain.service.SortDirection
import com.example.fintrack.domain.service.SortField
import com.example.fintrack.domain.service.SortSpec
import com.example.fintrack.domain.service.UnresolvedDataReportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stage 9 P20 — search / diagnostics ViewModel.
 *
 * Owns filter state (survives navigation via the shell's saveState/restore),
 * bounded pagination, the reconciliation workbench, the unresolved-data
 * report and the raw-evidence viewer. All actions are read-only; evidence
 * text is redacted through [RedactionEngine] before it is ever copied out.
 */
class SearchViewModel(
    private val repository: RoomInsightsRepository,
    private val reconciliationService: ReconciliationService = ReconciliationService(),
) : ViewModel() {

    data class Filters(
        val textQuery: String = "",
        val fromDay: Long? = null,
        val toDay: Long? = null,
        val accountId: String? = null,
        val kind: TxKind? = null,
        val tag: String? = null,
        val sortField: SortField = SortField.OCCURRED_AT,
        val sortDirection: SortDirection = SortDirection.DESC,
    )

    data class State(
        val loading: Boolean = false,
        val filters: Filters = Filters(),
        val rows: List<SearchResultRow> = emptyList(),
        val totalMatching: Int = 0,
        val pageSize: Int = 100,
        val accounts: List<RoomInsightsRepository.AccountEntityProjection> = emptyList(),
        val availableTags: List<String> = emptyList(),
        val reconciliation: List<ReconciliationService.AccountReconciliation> = emptyList(),
        val unresolved: UnresolvedDataReportService.Report? = null,
        val evidence: List<RoomInsightsRepository.EvidenceRecord> = emptyList(),
        val evidenceRedactedForCopy: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var currentOffset = 0

    init {
        refreshMeta()
        runSearch(reset = true)
    }

    fun updateFilters(transform: (Filters) -> Filters) {
        _state.value = _state.value.copy(filters = transform(_state.value.filters))
        runSearch(reset = true)
    }

    fun loadMore() {
        if (_state.value.loading) return
        currentOffset += _state.value.pageSize
        runSearch(reset = false)
    }

    fun refresh() {
        refreshMeta()
        runSearch(reset = true)
    }

    private fun refreshMeta() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    accounts = repository.accounts(),
                    availableTags = repository.distinctTagsInUse(),
                )
            } catch (_: Throwable) {
                // meta is best-effort; search still works without it
            }
        }
    }

    private fun runSearch(reset: Boolean) {
        if (reset) currentOffset = 0
        val f = _state.value.filters
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val (rows, total) = repository.search(
                    textQuery = f.textQuery.ifBlank { null },
                    fromDay = f.fromDay,
                    toDay = f.toDay,
                    accountIds = f.accountId?.let { setOf(it) },
                    kinds = f.kind?.let { setOf(it) },
                    tags = f.tag?.let { setOf(it) },
                    sort = SortSpec(f.sortField, f.sortDirection),
                    limit = _state.value.pageSize,
                    offset = currentOffset,
                )
                _state.value = _state.value.copy(
                    loading = false,
                    rows = if (reset) rows else _state.value.rows + rows,
                    totalMatching = total,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    // ---- P20 #5: reconciliation workbench (read-only) ----

    fun loadReconciliation() {
        viewModelScope.launch {
            try {
                val recs = repository.accounts().filter { it.lifecycle == "ACTIVE" }
                    .mapNotNull { repository.reconciliation(it.id) }
                _state.value = _state.value.copy(reconciliation = recs)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t::class.java.simpleName)
            }
        }
    }

    // ---- P20 #6: unresolved-data report ----

    fun loadUnresolvedReport() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    unresolved = repository.unresolvedReport(System.currentTimeMillis()),
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t::class.java.simpleName)
            }
        }
    }

    // ---- P20 #7/#8: raw evidence viewer ----

    /** Loads immutable evidence + provenance for one transaction. */
    fun openEvidence(transactionId: String) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(evidence = repository.rawEvidenceFor(transactionId))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t::class.java.simpleName)
            }
        }
    }

    /**
     * Produces a REDACTED copy of the evidence for export/sharing. The raw
     * body is never placed into UI-copy state unredacted.
     */
    fun copyEvidenceRedacted() {
        val redacted = _state.value.evidence.joinToString("\n---\n") { e ->
            val r = RedactionEngine.redact(e.body)
            buildString {
                append("sender=").append(RedactionEngine.sha256(e.sender ?: "unknown").take(16))
                append(" at=").append(e.receivedAtEpochMs)
                append('\n').append(r.redactedText)
            }
        }
        _state.value = _state.value.copy(evidenceRedactedForCopy = redacted)
    }

    fun clearEvidence() {
        _state.value = _state.value.copy(evidence = emptyList(), evidenceRedactedForCopy = null)
    }
}
