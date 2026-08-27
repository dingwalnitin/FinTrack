package com.example.fintrack.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.domain.FinanceRepository
import com.example.fintrack.domain.model.Transaction
import com.example.fintrack.ui.common.UiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UI state derived from repository Flow (cached local data first).
 * UI never touches Room directly.
 */
class TransactionsViewModel(repository: FinanceRepository) : ViewModel() {

    val state: StateFlow<UiState<List<Transaction>>> = repository.observeTransactions()
        .map { list ->
            if (list.isEmpty()) UiState.Empty else UiState.Content(list)
        }
        .catch { UiState.Error(it.message ?: "Unknown error") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)
}
