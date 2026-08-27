package com.example.fintrack.application.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.data.repository.RoomInsightsRepository
import com.example.fintrack.domain.model.BudgetProgress
import com.example.fintrack.domain.service.BudgetSink
import com.example.fintrack.domain.service.InsightsEngine
import com.example.fintrack.domain.service.LedgerTxnView
import com.example.fintrack.domain.service.ReviewQueueService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stage 9 P19 — Home dashboard ViewModel.
 *
 * All aggregates are derived locally from ledger facts through the read-only
 * insights repository + pure [InsightsEngine]. No network, no Room in UI.
 */
class HomeViewModel(
    private val repository: RoomInsightsRepository,
    private val budgetSink: BudgetSink?,
    private val reviewQueueService: ReviewQueueService?,
    private val engine: InsightsEngine = InsightsEngine(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    data class BudgetCard(
        val name: String,
        val progress: BudgetProgress,
    )

    data class State(
        val loading: Boolean = true,
        val currencyCode: String = "INR",
        val monthStartEpochDay: Long = 0,
        val monthEndEpochDay: Long = 0,
        val totalBalanceMinor: Long? = null,
        val incomeNetMinor: Long = 0,
        val spendGrossMinor: Long = 0,
        val spendRefundedMinor: Long = 0,
        val spendNetMinor: Long = 0,
        val recent: List<LedgerTxnView> = emptyList(),
        val openReviewCount: Int = 0,
        val pendingStatusCount: Int = 0,
        val budgets: List<BudgetCard> = emptyList(),
        val coverageIncomplete: Boolean = false,
        val error: String? = null,
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
                val today = LocalDate.now(zone)
                val monthStart = today.withDayOfMonth(1)
                val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
                val fromDay = monthStart.toEpochDay()
                val toDay = monthEnd.toEpochDay()

                val txns = repository.ledgerViews(fromDay, toDay)
                val balances = repository.totalBalanceByAccount()
                val totalBalance = if (balances.isEmpty()) null else balances.values.sum()

                val summary = engine.dashboardSummary(
                    txns = txns,
                    fromDay = fromDay,
                    toDay = toDay,
                    currencyCode = dominantCurrency(txns),
                    totalBalanceMinor = totalBalance,
                    openReviewCount = reviewQueueService?.openItems()?.size ?: 0,
                    pendingStatusCount = 0,
                    recentLimit = 5,
                    coverageIncomplete = false,
                )

                // Budget cards derived from policy + this month's ledger slice.
                val cards = budgetSink?.activeBudgets().orEmpty().mapNotNull { budget ->
                    val period = com.example.fintrack.domain.service.BudgetService()
                        .periodContaining(budget, today)
                    val windowed = repository.ledgerViews(period.first.toEpochDay(), period.second.toEpochDay())
                    val progress = com.example.fintrack.domain.service.BudgetService().progress(
                        budget = budget,
                        txnsInPeriod = windowed.map { toBudgetTxn(it) },
                        rolloverInMinor = 0L,
                        coverageIncomplete = false,
                    )
                    BudgetCard(budget.name, progress)
                }

                _state.value = State(
                    loading = false,
                    currencyCode = summary.currencyCode,
                    monthStartEpochDay = fromDay,
                    monthEndEpochDay = toDay,
                    totalBalanceMinor = summary.totalBalanceMinor,
                    incomeNetMinor = summary.incomeNetMinor,
                    spendGrossMinor = summary.spendGrossMinor,
                    spendRefundedMinor = summary.spendRefundedMinor,
                    spendNetMinor = summary.spendNetMinor,
                    recent = summary.recentTransactions,
                    openReviewCount = summary.openReviewCount,
                    pendingStatusCount = summary.pendingStatusCount,
                    budgets = cards,
                    coverageIncomplete = summary.coverageIncomplete,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    private fun toBudgetTxn(t: LedgerTxnView) =
        com.example.fintrack.domain.service.BudgetService.TxnView(
            id = t.id,
            accountId = t.accountId,
            categoryId = t.categoryId,
            kind = runCatching { com.example.fintrack.domain.model.TxKind.valueOf(t.kind) }
                .getOrDefault(com.example.fintrack.domain.model.TxKind.UNKNOWN),
            directionDebit = t.directionDebit,
            amountMinor = t.amountMinor,
            localDateEpochDay = t.localDateEpochDay,
            statusDeleted = t.statusDeleted,
        )

    private fun dominantCurrency(txns: List<LedgerTxnView>): String =
        txns.groupingBy { it.currencyCode }.eachCount().maxByOrNull { it.value }?.key ?: "INR"
}
