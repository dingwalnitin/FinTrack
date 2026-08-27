package com.example.fintrack.application.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.diagnostics.DiagnosticsReport
import com.example.fintrack.diagnostics.DiagnosticsService
import com.example.fintrack.diagnostics.FixtureDiff
import com.example.fintrack.diagnostics.ParserPlayground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stage 12 P25 — developer diagnostics ViewModel.
 *
 * Surfaces the diagnostics report, the parser playground (raw synthetic SMS
 * through classify/normalize/extract without touching the ledger), the
 * fixture corpus regression gate and the safe exportable text summary.
 *
 * Every action here is read-only with respect to the production ledger: the
 * playground runs parsers in memory, the corpus gate runs in memory, and
 * exporting uses the redacted [DiagnosticsService.exportAsText].
 */
class DiagnosticsViewModel(
    private val diagnosticsService: DiagnosticsService,
    private val playground: ParserPlayground = ParserPlayground(),
    private val diff: FixtureDiff = FixtureDiff(),
) : ViewModel() {

    data class State(
        val loading: Boolean = false,
        val report: DiagnosticsReport? = null,
        val error: String? = null,
        // ---- parser playground ----
        val playgroundInput: String = "",
        val playgroundResult: ParserPlayground.PlaygroundResult? = null,
        // ---- fixture regression gate ----
        val corpusResult: ParserPlayground.CorpusResult? = null,
        val diffResult: FixtureDiff.DiffResult? = null,
        // ---- export ----
        val exportText: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val report = diagnosticsService.buildReport()
                _state.value = _state.value.copy(loading = false, report = report)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: "diagnostics failed",
                )
            }
        }
    }

    /** Run the playground over the current input (or a seeded fixture). */
    fun runPlayground(input: String? = null) {
        val raw = input ?: _state.value.playgroundInput
        if (raw.isBlank()) return
        val result = playground.run(raw.trim())
        _state.value = _state.value.copy(
            playgroundInput = raw.trim(),
            playgroundResult = result,
        )
    }

    fun runCorpus() {
        val result = playground.runCorpus()
        _state.value = _state.value.copy(corpusResult = result)
    }

    fun runFixtureDiff() {
        val result = diff.diff()
        _state.value = _state.value.copy(diffResult = result)
    }

    fun exportSummary() {
        val report = _state.value.report ?: return
        _state.value = _state.value.copy(exportText = diagnosticsService.exportAsText(report))
    }

    fun clearExport() {
        _state.value = _state.value.copy(exportText = null)
    }
}
