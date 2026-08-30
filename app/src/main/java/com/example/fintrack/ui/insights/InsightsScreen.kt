package com.example.fintrack.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.insights.InsightsViewModel
import com.example.fintrack.domain.service.InsightsEngine
import com.example.fintrack.ui.common.BrandFilterChip
import com.example.fintrack.ui.common.DonutChart
import com.example.fintrack.ui.common.DonutSlice
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.RankedBarRow
import com.example.fintrack.ui.common.SectionHeader
import com.example.fintrack.ui.common.SegmentedTabs
import com.example.fintrack.ui.theme.Palette
import com.example.fintrack.ui.theme.categoryColor
import com.example.fintrack.ui.theme.categoryIcon

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
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Insights", style = MaterialTheme.typography.headlineSmall) }

        state.error?.let { err ->
            item { Text("Error: $err", color = Palette.Danger) }
        }

        // ---- grouping toggle ----
        item {
            SegmentedTabs(
                options = listOf("Category", "Merchant"),
                selectedIndex = if (state.grouping == InsightsViewModel.Grouping.CATEGORY) 0 else 1,
                onSelect = {
                    viewModel.setGrouping(
                        if (it == 0) InsightsViewModel.Grouping.CATEGORY else InsightsViewModel.Grouping.MERCHANT
                    )
                },
            )
        }

        // ---- account filter ----
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrandFilterChip(
                    selected = state.selectedAccountId == null,
                    onClick = { viewModel.setAccountFilter(null) },
                    label = "All accounts",
                )
                state.accounts.filter { it.lifecycle == "ACTIVE" }.take(4).forEach { acct ->
                    BrandFilterChip(
                        selected = state.selectedAccountId == acct.id,
                        onClick = { viewModel.setAccountFilter(acct.id) },
                        label = acct.nickname,
                    )
                }
            }
        }

        // ---- cash flow ----
        state.cashFlow?.let { cf ->
            item {
                FinTrackCard {
                    Text("Cash flow this month", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LabelValueRow("External in", "+₹${paise(cf.inflowExternalMinor)}", valueColor = Palette.Income)
                    LabelValueRow("External out", "₹${paise(cf.outflowExternalMinor)}")
                    if (cf.internalTransfersMinor > 0) {
                        LabelValueRow("Own transfers (not income/expense)", "₹${paise(cf.internalTransfersMinor)}")
                    }
                    Spacer(Modifier.height(4.dp))
                    LabelValueRow(
                        "Net",
                        "${if (cf.netExternalMinor >= 0) "+" else "−"}₹${paise(kotlin.math.abs(cf.netExternalMinor))}",
                        bold = true,
                        valueColor = if (cf.netExternalMinor >= 0) Palette.Income else MaterialTheme.colorScheme.onSurface,
                    )
                    state.previousCashFlow?.let { prev ->
                        val delta = cf.outflowExternalMinor - prev.outflowExternalMinor
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "vs previous equal-length period: spend " +
                                if (delta >= 0) "+₹${paise(delta)}" else "−₹${paise(-delta)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- savings rate ----
        state.savingsRate?.let { sr ->
            item {
                FinTrackCard {
                    Text("Savings rate", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    when {
                        sr.zeroIncome -> Text(
                            "No external income recorded this period — rate not computable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            "${(sr.rate!! * 100).toInt()}% of external income retained",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Palette.Income,
                        )
                    }
                    if (sr.incompleteHistory) {
                        Spacer(Modifier.height(4.dp))
                        Text("! History incomplete for this period", style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
                    }
                }
            }
        }

        // ---- breakdown ----
        state.breakdown?.let { bd ->
            item {
                SectionHeader(
                    if (state.grouping == InsightsViewModel.Grouping.CATEGORY) "Spending by category" else "Spending by merchant"
                )
            }
            if (state.grouping == InsightsViewModel.Grouping.CATEGORY) {
                item { CategoryDonutSection(bd.rows, state.categoryLabels) }
            } else {
                item { MerchantRankingSection(bd.rows) }
            }
        }

        // ---- rails ----
        state.railAnalytics?.let { ra ->
            item { SectionHeader("Payment rails") }
            item {
                FinTrackCard {
                    ra.rows.forEachIndexed { i, row ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(row.rail, style = MaterialTheme.typography.bodyMedium)
                                Text("${row.txnCount} txn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (row.fundingInstrumentMinor > 0) {
                                    Text(
                                        "of which ₹${paise(row.fundingInstrumentMinor)} funded by ${row.fundingInstrumentLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text("₹${paise(row.spendMinor)}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // ---- Pareto ----
        state.pareto?.takeIf { it.vitalFewCount > 0 }?.let { p ->
            item {
                FinTrackCard {
                    Text("Pareto", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${p.vitalFewCount} group(s) cover ≥${(p.threshold * 100).toInt()}% of net spend",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- income sources ----
        state.incomeSources?.takeIf { it.rows.isNotEmpty() }?.let { inc ->
            item { SectionHeader("Income sources") }
            item {
                FinTrackCard {
                    inc.rows.take(5).forEachIndexed { i, row ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        LabelValueRow(row.label ?: "Unknown source", "₹${paise(row.netMinor)}", valueColor = Palette.Income)
                    }
                }
            }
        }

        // ---- balance history summary ----
        item { SectionHeader("Balance history") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.balanceHistories.forEach { h ->
                    val last = h.points.lastOrNull()
                    FinTrackCard {
                        Text(h.accountId, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        last?.let {
                            Text(
                                "derived ₹${paise(it.derivedMinor)}" +
                                    (it.observedMinor?.let { obs -> " · observed ₹${paise(obs)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } ?: Text("No activity recorded yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (h.hasGaps) {
                            Text(
                                "! Gaps in history — observed balances are never interpolated",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Warn,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String, bold: Boolean = false, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (bold) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = if (bold) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** Donut chart + legend for the top categories, grouping the long tail into "Other". */
@Composable
private fun CategoryDonutSection(
    rows: List<InsightsEngine.BreakdownRow>,
    categoryLabels: Map<String, String>,
) {
    val labeled = rows.map { row ->
        val label = when {
            row.key == null -> "Uncategorized"
            else -> categoryLabels[row.key] ?: row.key
        }
        label to kotlin.math.abs(row.netMinor)
    }.sortedByDescending { it.second }

    val top = labeled.take(6)
    val otherTotal = labeled.drop(6).sumOf { it.second }
    val slices = (top + if (otherTotal > 0) listOf("Other" to otherTotal) else emptyList())
        .filter { it.second > 0 }
        .map { (label, amount) -> DonutSlice(label, amount.toFloat(), categoryColor(label)) }
    val total = slices.sumOf { it.value.toDouble() }.toLong()

    FinTrackCard {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DonutChart(slices = slices, modifier = Modifier.size(180.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("₹${paise(total)}", style = MaterialTheme.typography.titleLarge)
                    Text("total spend", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        slices.forEach { slice ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(slice.color))
                    Text(slice.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "₹${paise(slice.value.toLong())} · ${if (total > 0) (slice.value * 100 / total).toInt() else 0}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Ranked, proportional-bar list for the merchant breakdown. */
@Composable
private fun MerchantRankingSection(rows: List<InsightsEngine.BreakdownRow>) {
    val ranked = rows.sortedByDescending { kotlin.math.abs(it.netMinor) }
    val max = ranked.maxOfOrNull { kotlin.math.abs(it.netMinor) }?.coerceAtLeast(1L) ?: 1L

    FinTrackCard {
        ranked.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            val label = row.key ?: "Uncategorized"
            RankedBarRow(
                label = label,
                amountText = "₹${paise(kotlin.math.abs(row.netMinor))}",
                ratio = kotlin.math.abs(row.netMinor).toFloat() / max.toFloat(),
                color = categoryColor(label),
                icon = categoryIcon(label),
                subtitle = "${row.txnCount} txn · ${(row.shareOfNet * 100).toInt()}%",
            )
        }
    }
}

private fun paise(minor: Long): String {
    val abs = kotlin.math.abs(minor)
    val sign = if (minor < 0) "-" else ""
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
