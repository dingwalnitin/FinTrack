package com.example.fintrack.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
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
import com.example.fintrack.domain.service.TransactionFilter
import com.example.fintrack.ui.common.BrandFilterChip
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.common.ErrorState
import com.example.fintrack.ui.common.FinTrackCard
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
 * client-side search + income/expense filter grouped by day. Stage 13 (B)
 * adds sort controls (date/amount/merchant, asc/desc) and a filter panel
 * (rail, min/max amount). The INCOME/EXPENSE axis always derives from
 * [Transaction.directionDebit] — never from the sign of `amountMinor`.
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
    var showFilters by remember { mutableStateOf(false) }
    var selectedRail by remember { mutableStateOf<String?>(null) }
    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var sortField by remember { mutableStateOf(TransactionFilter.SortField.DATE) }
    var sortAsc by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    val availableRails = remember(all) {
        all.mapNotNull { it.rail }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val filters = TransactionFilter.Filters(
        kind = when (filter) {
            TxnFilter.ALL -> TransactionFilter.KindFilter.ALL
            TxnFilter.INCOME -> TransactionFilter.KindFilter.INCOME
            TxnFilter.EXPENSE -> TransactionFilter.KindFilter.EXPENSE
        },
        query = query,
        rail = selectedRail,
        minAmountMinor = minAmountText.toLongOrNull(),
        maxAmountMinor = maxAmountText.toLongOrNull(),
    )
    val sort = TransactionFilter.SortSpec(
        field = sortField,
        order = if (sortAsc) TransactionFilter.SortOrder.ASC else TransactionFilter.SortOrder.DESC,
    )

    val filtered = remember(all, filters, sort) {
        TransactionFilter.apply(all, filters, sort)
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
                "${all.size} total · ${filtered.size} shown",
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
                BrandFilterChip(
                    selected = showFilters,
                    onClick = { showFilters = !showFilters },
                    label = "Filters",
                )
            }
        }

        // ---- Stage 13 (B): sort + advanced filter panel ----
        if (showFilters) {
            item {
                FinTrackCard {
                    Text("Sort by", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            TransactionFilter.SortField.DATE to "Date",
                            TransactionFilter.SortField.AMOUNT to "Amount",
                            TransactionFilter.SortField.MERCHANT to "Merchant A-Z",
                        ).forEach { (field, label) ->
                            FilterChip(
                                selected = sortField == field,
                                onClick = { sortField = field },
                                label = { Text(label) },
                            )
                        }
                        FilterChip(
                            selected = sortAsc,
                            onClick = { sortAsc = !sortAsc },
                            label = { Text(if (sortAsc) "Asc" else "Desc") },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Min amount (₹)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = minAmountText,
                        onValueChange = { minAmountText = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 500") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Max amount (₹)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = maxAmountText,
                        onValueChange = { maxAmountText = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 10000") },
                        singleLine = true,
                    )

                    if (availableRails.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Rail", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BrandFilterChip(
                                selected = selectedRail == null,
                                onClick = { selectedRail = null },
                                label = "All rails",
                            )
                            availableRails.forEach { rail ->
                                BrandFilterChip(
                                    selected = selectedRail == rail,
                                    onClick = { selectedRail = if (selectedRail == rail) null else rail },
                                    label = rail,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Direction always uses the semantic kind, never the amount sign.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
