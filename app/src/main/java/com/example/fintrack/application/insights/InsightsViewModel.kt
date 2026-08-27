package com.example.fintrack.application.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.data.repository.RoomInsightsRepository
import com.example.fintrack.domain.service.InsightsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stage 9 P19 — Insights ViewModel: cash flow, category/merchant/rail
 * breakdowns, savings rate, period comparison, balance history and Pareto.
 *
 * All computation is local and derived from ledger facts; incomplete coverage
 * is surfaced as data on every metric that can be affected by it.
 */
class InsightsViewModel(
    private val repository: RoomInsightsRepository,
    private val engine: InsightsEngine = InsightsEngine(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    enum class Grouping { CATEGORY, MERCHANT }

    data class State(
        val loading: Boolean = true,
        val currencyCode: String = "INR",
        val fromDay: Long = 0,
        val toDay: Long = 0,
        val grouping: Grouping = Grouping.CATEGORY,
        val selectedAccountId: String? = null,
        val accounts: List<RoomInsightsRepository.AccountEntityProjection> = emptyList(),
        val categoryLabels: Map<String, String> = emptyMap(),
        val cashFlow: InsightsEngine.CashFlow? = null,
        val previousCashFlow: InsightsEngine.CashFlow? = null,
        val breakdown: InsightsEngine.Breakdown? = null,
        val railAnalytics: InsightsEngine.RailAnalytics? = null,
        val savingsRate: InsightsEngine.SavingsRate? = null,
        val pareto: InsightsEngine.Pareto? = null,
        val incomeSources: InsightsEngine.Breakdown? = null,
        val calendar: List<InsightsEngine.CalendarDay> = emptyList(),
        val balanceHistories: List<InsightsEngine.BalanceHistory> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setGrouping(grouping: Grouping) {
        _state.value = _state.value.copy(grouping = grouping)
        refresh()
    }

    fun setAccountFilter(accountId: String?) {
        _state.value = _state.value.copy(selectedAccountId = accountId)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val today = LocalDate.now(zone)
                val monthStart = today.withDayOfMonth(1)
                val fromDay = monthStart.toEpochDay()
                val toDay = today.toEpochDay()
                val accountFilter = _state.value.selectedAccountId?.let { setOf(it) }
                val currency = "INR"

                val txns = repository.ledgerViews(fromDay, toDay)
                val labels = repository.categoryLabels()

                val comparison = engine.compareCashFlow(
                    txns, fromDay, toDay, currency, coverageIncomplete = false,
                )
                val breakdown = engine.spendBreakdown(
                    txns, fromDay, toDay,
                    grouping = when (_state.value.grouping) {
                        Grouping.CATEGORY -> InsightsEngine.Grouping.CATEGORY
                        Grouping.MERCHANT -> InsightsEngine.Grouping.MERCHANT
                    },
                    accountIds = accountFilter,
                    currencyCode = currency,
                )
                val rails = engine.railAnalytics(
                    txns, fromDay, toDay, accountIds = accountFilter, currencyCode = currency,
                )
                val savings = engine.savingsRate(txns, fromDay, toDay)
                val income = engine.incomeSources(txns, fromDay, toDay, currency)

                // Balance history per account (observed snapshots + derived points).
                val openings = repository.openingBalances()
                val snapshots = repository.snapshotsByAccount()
                val allTxns = repository.ledgerViews()
                val histories = repository.accounts().filter { it.lifecycle == "ACTIVE" }.map { acct ->
                    engine.balanceHistory(
                        accountId = acct.id,
                        openingBalanceMinor = openings[acct.id],
                        postings = allTxns.filter { it.accountId == acct.id },
                        snapshots = snapshots[acct.id].orEmpty(),
                        currencyCode = acct.currencyCode,
                    )
                }

                _state.value = _state.value.copy(
                    loading = false,
                    currencyCode = currency,
                    fromDay = fromDay,
                    toDay = toDay,
                    accounts = repository.accounts(),
                    categoryLabels = labels,
                    cashFlow = comparison.current,
                    previousCashFlow = comparison.previous,
                    breakdown = breakdown,
                    railAnalytics = rails,
                    savingsRate = savings,
                    pareto = engine.pareto(breakdown),
                    incomeSources = income,
                    calendar = engine.cashFlowCalendar(txns, fromDay, toDay),
                    balanceHistories = histories,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }
}
