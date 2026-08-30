package com.example.fintrack.ui.insights

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.insights.InsightsViewModel

/**
 * Stage 9 P19 — Insights screen: monthly cash flow (external vs internal),
 * category/merchant breakdown with drill-down labels, rail analytics with
 * card-funded share separated, savings rate, Pareto and balance history.
 *
 * Every metric states its coverage; zero-income and incomplete-history
 * states are explicit text cues, never fabricated numbers.
 */
@Composable
fun InsightsScreen(viewModel: InsightsViewModel) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Insights", style = MaterialTheme.typography.titleLarge) }

        state.error?.let { err ->
            item { Text("Error: $err", color = MaterialTheme.colorScheme.error) }
        }

        // ---- grouping + account filters ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.grouping == InsightsViewModel.Grouping.CATEGORY,
                    onClick = { viewModel.setGrouping(InsightsViewModel.Grouping.CATEGORY) },
                    label = { Text("By category") },
                )
                FilterChip(
                    selected = state.grouping == InsightsViewModel.Grouping.MERCHANT,
                    onClick = { viewModel.setGrouping(InsightsViewModel.Grouping.MERCHANT) },
                    label = { Text("By merchant") },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedAccountId == null,
                    onClick = { viewModel.setAccountFilter(null) },
                    label = { Text("All accounts") },
                )
                state.accounts.filter { it.lifecycle == "ACTIVE" }.take(4).forEach { acct ->
                    FilterChip(
                        selected = state.selectedAccountId == acct.id,
                        onClick = { viewModel.setAccountFilter(acct.id) },
                        label = { Text(acct.nickname) },
                    )
                }
            }
        }

        // ---- cash flow ----
        state.cashFlow?.let { cf ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Cash flow this month", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("External in")
                            Text("+₹${paise(cf.inflowExternalMinor)}")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("External out")
                            Text("₹${paise(cf.outflowExternalMinor)}")
                        }
                        if (cf.internalTransfersMinor > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Own transfers (not income/expense)")
                                Text("₹${paise(cf.internalTransfersMinor)}")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Net", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${if (cf.netExternalMinor >= 0) "+" else "−"}₹${paise(kotlin.math.abs(cf.netExternalMinor))}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        state.previousCashFlow?.let { prev ->
                            val delta = cf.outflowExternalMinor - prev.outflowExternalMinor
                            Text(
                                "vs previous equal-length period: spend " +
                                    if (delta >= 0) "+₹${paise(delta)}" else "−₹${paise(-delta)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // ---- savings rate ----
        state.savingsRate?.let { sr ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Savings rate", style = MaterialTheme.typography.titleMedium)
                        when {
                            sr.zeroIncome -> Text(
                                "No external income recorded this period — rate not computable",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            else -> Text("${(sr.rate!! * 100).toInt()}% of external income retained")
                        }
                        if (sr.incompleteHistory) {
                            Text(
                                "! History incomplete for this period",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // ---- breakdown ----
        state.breakdown?.let { bd ->
            item { Text("Spending ${if (state.grouping == InsightsViewModel.Grouping.CATEGORY) "by category" else "by merchant"}", style = MaterialTheme.typography.titleMedium) }
            items(bd.rows.size) { i ->
                val row = bd.rows[i]
                val label = when {
                    row.key == null -> "Uncategorized"
                    state.grouping == InsightsViewModel.Grouping.CATEGORY ->
                        state.categoryLabels[row.key] ?: row.key
                    else -> row.key
                }
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(label)
                            Text(
                                "${row.txnCount} txn · ${(row.shareOfNet * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("₹${paise(row.netMinor)}")
                            if (row.refundedMinor > 0) {
                                Text(
                                    "gross ₹${paise(row.grossMinor)} − refunds ₹${paise(row.refundedMinor)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- rails ----
        state.railAnalytics?.let { ra ->
            item { Text("Payment rails", style = MaterialTheme.typography.titleMedium) }
            items(ra.rows.size) { i ->
                val row = ra.rows[i]
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(row.rail)
                            Text("${row.txnCount} txn", style = MaterialTheme.typography.bodySmall)
                            if (row.fundingInstrumentMinor > 0) {
                                Text(
                                    "of which ₹${paise(row.fundingInstrumentMinor)} funded by ${row.fundingInstrumentLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text("₹${paise(row.spendMinor)}")
                    }
                }
            }
        }

        // ---- Pareto ----
        state.pareto?.takeIf { it.vitalFewCount > 0 }?.let { p ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Pareto", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${p.vitalFewCount} group(s) cover ≥${(p.threshold * 100).toInt()}% of net spend",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // ---- income sources ----
        state.incomeSources?.takeIf { it.rows.isNotEmpty() }?.let { inc ->
            item { Text("Income sources", style = MaterialTheme.typography.titleMedium) }
            items(inc.rows.take(5).size) { i ->
                val row = inc.rows[i]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(row.label ?: "Unknown source")
                    Text("₹${paise(row.netMinor)}")
                }
            }
        }

        // ---- balance history summary ----
        item { Text("Balance history", style = MaterialTheme.typography.titleMedium) }
        items(state.balanceHistories.size) { i ->
            val h = state.balanceHistories[i]
            val last = h.points.lastOrNull()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(h.accountId, style = MaterialTheme.typography.bodyMedium)
                    last?.let {
                        Text(
                            "derived ₹${paise(it.derivedMinor)}" +
                                (it.observedMinor?.let { obs -> " · observed ₹${paise(obs)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } ?: Text("No activity recorded yet", style = MaterialTheme.typography.bodySmall)
                    if (h.hasGaps) {
                        Text(
                            "! Gaps in history — observed balances are never interpolated",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun paise(minor: Long): String {
    val abs = kotlin.math.abs(minor)
    val sign = if (minor < 0) "-" else ""
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
