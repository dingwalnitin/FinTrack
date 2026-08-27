package com.example.fintrack.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.insights.HomeViewModel
import com.example.fintrack.domain.model.ProgressStatus
import com.example.fintrack.ui.common.MoneyRow
import com.example.fintrack.ui.common.MoneyRowData

/**
 * Stage 9 P19 — Home dashboard.
 *
 * Local aggregates only: balances, month spend/income, budget progress and
 * review/pending counts. No decorative charts; every card is a number the
 * user can act on. Incomplete coverage is stated, never hidden.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTransactions: () -> Unit = {},
    onOpenReview: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Home", style = MaterialTheme.typography.titleLarge) }

        state.error?.let { err ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Error: $err",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        if (state.loading && state.recent.isEmpty()) {
            item { Text("Loading…", style = MaterialTheme.typography.bodyMedium) }
        }

        // ---- balances ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total balance", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = state.totalBalanceMinor?.let { "₹${paise(it)} ${state.currencyCode}" }
                            ?: "No accounts yet",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics {
                            contentDescription = state.totalBalanceMinor?.let {
                                "Total balance ${paise(it)} ${state.currencyCode}"
                            } ?: "No accounts yet"
                        },
                    )
                }
            }
        }

        // ---- this month's flows ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This month", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Income")
                        Text("+₹${paise(state.incomeNetMinor)}", color = MaterialTheme.colorScheme.secondary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Spend (gross)")
                        Text("₹${paise(state.spendGrossMinor)}")
                    }
                    if (state.spendRefundedMinor > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Refunds")
                            Text("−₹${paise(state.spendRefundedMinor)}")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Spend (net)", style = MaterialTheme.typography.labelLarge)
                            Text("₹${paise(state.spendNetMinor)}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // ---- budget progress ----
        if (state.budgets.isNotEmpty()) {
            item { Text("Budgets", style = MaterialTheme.typography.titleMedium) }
            items(state.budgets, key = { it.name }) { card ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(card.name, style = MaterialTheme.typography.bodyLarge)
                            Text(statusLabel(card.progress.status), style = MaterialTheme.typography.labelLarge)
                        }
                        LinearProgressIndicator(
                            progress = { card.progress.usageRatio.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Text(
                            "₹${paise(card.progress.effectiveUsageMinor)} of ₹${paise(card.progress.targetMinor)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // ---- review / pending counts ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Needs attention", style = MaterialTheme.typography.titleMedium)
                    Text("${state.openReviewCount} open review item(s)")
                    Text("${state.pendingStatusCount} pending interpretation(s)")
                }
            }
        }

        // ---- recent transactions ----
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(state.recent, key = { it.id }) { txn ->
            MoneyRow(
                MoneyRowData(
                    title = txn.merchant ?: txn.counterpartyNormalized ?: "Transaction",
                    amountMinor = if (txn.directionDebit) -txn.amountMinor else txn.amountMinor,
                    currencyCode = txn.currencyCode,
                    isDebit = txn.directionDebit,
                )
            )
        }

        if (state.coverageIncomplete) {
            item {
                Text(
                    "! Transaction history is incomplete — figures may be understated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusLabel(status: ProgressStatus): String = "${status.symbol} ${status.label}"

private fun paise(minor: Long): String {
    val abs = kotlin.math.abs(minor)
    val sign = if (minor < 0) "-" else ""
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
