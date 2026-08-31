package com.example.fintrack.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.Transaction
import com.example.fintrack.ui.common.BrandFilterChip
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.common.ErrorState
import com.example.fintrack.ui.common.LoadingSkeleton
import com.example.fintrack.ui.common.MoneyRow
import com.example.fintrack.ui.common.MoneyRowData
import com.example.fintrack.ui.common.ReviewBanner
import com.example.fintrack.ui.common.UiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TxnFilter { ALL, INCOME, EXPENSE }

/**
 * Transactions route: dense list rendering every common UI state, with a
 * lightweight client-side search + income/expense filter grouped by day.
 */
@Composable
fun TransactionsRoute(
    state: UiState<List<Transaction>>,
    onOpenTransaction: (String) -> Unit = {},
) {
    when (state) {
        is UiState.Loading -> LoadingSkeleton()
        is UiState.Empty -> EmptyState()
        is UiState.Error -> ErrorState(message = state.message, onRetry = null, onDismiss = null)
        is UiState.Review -> ReviewBanner(reason = state.reason.name, onReview = {})
        is UiState.Content -> TransactionsList(state.data, onOpenTransaction)
    }
}

@Composable
private fun TransactionsList(all: List<Transaction>, onOpenTransaction: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(TxnFilter.ALL) }
    val zone = remember { ZoneId.systemDefault() }

    val filtered = remember(all, query, filter) {
        all.filter { txn ->
            val matchesQuery = query.isBlank() ||
                (txn.counterparty?.contains(query, ignoreCase = true) == true)
            val matchesFilter = when (filter) {
                TxnFilter.ALL -> true
                TxnFilter.INCOME -> !txn.directionDebit
                TxnFilter.EXPENSE -> txn.directionDebit
            }
            matchesQuery && matchesFilter
        }.sortedByDescending { it.occurredAt }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.occurredAt.atZone(zone).toLocalDate() } }
    val today = remember(zone) { LocalDate.now(zone) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
    ) {
        item {
            Text("Transactions", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${all.size} total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search transactions") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    TxnFilter.ALL to "All",
                    TxnFilter.INCOME to "Income",
                    TxnFilter.EXPENSE to "Expense",
                ).forEach { (f, label) ->
                    BrandFilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = label,
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    "No transactions match your filters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        grouped.forEach { (date, txns) ->
            item {
                Text(
                    dateHeaderLabel(date, today),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(txns, key = { it.id.value }) { txn ->
                MoneyRow(
                    MoneyRowData(
                        title = txn.counterparty ?: "Transaction",
                        amountMinor = if (txn.directionDebit) -txn.amount.minorUnits else txn.amount.minorUnits,
                        currencyCode = txn.amount.currencyCode,
                        isDebit = txn.directionDebit,
                        subtitle = timeLabel(txn.occurredAt, zone),
                    ),
                    onClick = { onOpenTransaction(txn.id.value) },
                )
            }
        }
    }
}

private fun dateHeaderLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
}

private fun timeLabel(instant: Instant, zone: ZoneId): String =
    instant.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))
