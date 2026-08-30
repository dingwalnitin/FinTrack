package com.example.fintrack.ui.budgets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.ProgressStatus
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.ProgressRing
import com.example.fintrack.ui.common.SectionHeader
import com.example.fintrack.ui.common.StatusPill
import com.example.fintrack.ui.common.statusColor
import com.example.fintrack.ui.theme.Palette

/**
 * Stage 8 P16 — budgets list with actual-vs-budget progress.
 *
 * Accessibility: status is never color-only — every row carries a text label
 * and symbol (OK / ! / X) plus a contentDescription for screen readers.
 * Rollover source is shown explicitly when present ("incl. ₹X carried in").
 */
@Composable
fun BudgetsScreen(viewModel: BudgetsViewModel) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Budgets", style = MaterialTheme.typography.headlineSmall) }

        if (state.budgets.isEmpty()) {
            item { EmptyState("No budgets yet — create one to track spending against a monthly target.") }
        } else {
            item { OverallBudgetCard(state.budgets) }
            item { SectionHeader("Your budgets") }
        }

        items(state.budgets, key = { it.name }) { row -> BudgetCard(row) }

        state.forecast?.let { f ->
            item {
                FinTrackCard {
                    Text("Upcoming recurring (estimate)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "₹${paiseToRupees(f.expectedTotalMinor)} expected this period" +
                            if (f.includesUnconfirmed) " (includes unconfirmed patterns)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    f.upcoming.take(5).forEach { u ->
                        Text(
                            "• ${u.merchant ?: u.counterpartyNormalized ?: "Unknown payee"}: " +
                                "₹${paiseToRupees(u.expectedAmountMinor)}" +
                                (if (!u.confirmed) " (?)" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverallBudgetCard(budgets: List<BudgetsViewModel.BudgetRow>) {
    val totalTarget = budgets.sumOf { it.progress.targetMinor }
    val totalUsed = budgets.sumOf { it.progress.effectiveUsageMinor }
    val ratio = if (totalTarget <= 0) 0f else (totalUsed.toDouble() / totalTarget.toDouble()).toFloat()
    val worstStatus = budgets.map { it.progress.status }.maxByOrNull {
        when (it) { ProgressStatus.OVER -> 2; ProgressStatus.NEAR_LIMIT -> 1; ProgressStatus.UNDER -> 0 }
    } ?: ProgressStatus.UNDER

    FinTrackCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ProgressRing(
                progress = ratio,
                modifier = Modifier.size(88.dp),
                strokeWidth = 10.dp,
                progressColor = statusColor(worstStatus),
            ) {
                Text(
                    "${(ratio * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = "${(ratio * 100).toInt()} percent of total budget used" },
                )
            }
            Column {
                Text("Total spent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("₹${paiseToRupees(totalUsed)}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "of ₹${paiseToRupees(totalTarget)} budgeted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BudgetCard(row: BudgetsViewModel.BudgetRow) {
    FinTrackCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            StatusPill(row.progress.status)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Palette.SurfaceHigh)
                .semantics {
                    contentDescription =
                        "Budget ${row.name}: ${row.progress.status.label}, " +
                            "${(row.progress.usageRatio * 100).toInt()} percent used"
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth(row.progress.usageRatio.toFloat().coerceIn(0f, 1f))
                    .fillMaxSize()
                    .clip(RoundedCornerShape(5.dp))
                    .background(statusColor(row.progress.status)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "₹${paiseToRupees(row.progress.effectiveUsageMinor)} of ₹${paiseToRupees(row.progress.targetMinor)}" +
                if (row.progress.rolloverInMinor > 0) {
                    " (incl. ₹${paiseToRupees(row.progress.rolloverInMinor)} carried in)"
                } else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.progress.coverageIncomplete) {
            Text(
                "! Partial transaction history — figures may be understated",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Warn,
            )
        }
    }
}

private fun paiseToRupees(minor: Long): String {
    val r = minor / 100
    val p = minor % 100
    return if (p == 0L) "$r" else "$r.${p.toString().padStart(2, '0')}"
}
