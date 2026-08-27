package com.example.fintrack.ui.transactions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.fintrack.domain.model.Transaction
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.common.ErrorState
import com.example.fintrack.ui.common.LoadingSkeleton
import com.example.fintrack.ui.common.MoneyRow
import com.example.fintrack.ui.common.MoneyRowData
import com.example.fintrack.ui.common.ReviewBanner
import com.example.fintrack.ui.common.UiState

/**
 * Transactions route: dense list rendering every common UI state.
 * Debit/credit direction is derived from the sign of the stored amount.
 */
@Composable
fun TransactionsRoute(state: UiState<List<Transaction>>) {
    when (state) {
        is UiState.Loading -> LoadingSkeleton()
        is UiState.Empty -> EmptyState()
        is UiState.Error -> ErrorState(message = state.message, onRetry = null, onDismiss = null)
        is UiState.Review -> ReviewBanner(reason = state.reason.name, onReview = {})
        is UiState.Content -> LazyColumn(Modifier.fillMaxSize()) {
            items(state.data, key = { it.id.value }) { txn ->
                MoneyRow(
                    MoneyRowData(
                        title = txn.counterparty ?: "Transaction",
                        amountMinor = txn.amount.minorUnits,
                        currencyCode = txn.amount.currencyCode,
                        isDebit = txn.amount.minorUnits < 0,
                    )
                )
            }
        }
    }
}
