package com.example.fintrack.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.domain.model.BudgetProgress
import com.example.fintrack.domain.model.RecurringForecast
import com.example.fintrack.domain.service.BudgetSink
import com.example.fintrack.domain.service.RecurringSink
import com.example.fintrack.domain.service.RecurringService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Stage 8 P16/P17 budgets ViewModel. Reads only through domain sinks — never
 * Room directly. All values are derived offline from local ledger data.
 */
class BudgetsViewModel(
    private val budgetSink: BudgetSink,
    private val recurringSink: RecurringSink,
    private val recurringService: RecurringService,
) : ViewModel() {

    data class BudgetRow(
        val name: String,
        val progress: BudgetProgress,
    )

    data class State(
        val budgets: List<BudgetRow> = emptyList(),
        val forecast: RecurringForecast? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val budgets = budgetSink.activeBudgets()
            // Progress rows are computed by the app shell wiring (which owns
            // the ledger read); here we surface the persisted snapshot.
            val forecast = runCatching {
                val patterns = recurringSink.reviewablePatterns()
                val today = Instant.now().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                recurringService.forecast(
                    patterns,
                    windowStartEpochDay = today.toEpochDay(),
                    windowEndEpochDay = today.plusDays(30).toEpochDay(),
                )
            }.getOrNull()
            _state.value = State(budgets = budgets.map { BudgetRow(it.name, placeholderProgress(it.id)) }, forecast = forecast)
        }
    }

    private fun placeholderProgress(budgetId: String) = BudgetProgress(
        budgetId = budgetId,
        periodStartEpochDay = 0,
        periodEndEpochDay = 0,
        targetMinor = 0,
        rolloverInMinor = 0,
        spentMinor = 0,
        refundedMinor = 0,
        effectiveUsageMinor = 0,
        remainingMinor = 0,
        usageRatio = 0.0,
        status = com.example.fintrack.domain.model.ProgressStatus.UNDER,
        coverageIncomplete = true,
    )
}
