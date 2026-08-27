package com.example.fintrack.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Budgets", style = MaterialTheme.typography.titleLarge)

        if (state.budgets.isEmpty()) {
            Text(
                "No budgets yet. Create one to track spending against a monthly target.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.budgets.forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text(statusLabel(row.progress.status), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(
                        progress = { row.progress.usageRatio.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .semantics {
                                contentDescription =
                                    "Budget ${row.name}: ${statusLabel(row.progress.status)}, " +
                                        "${(row.progress.usageRatio * 100).toInt()} percent used"
                            },
                    )
                    Text(
                        "₹${paiseToRupees(row.progress.effectiveUsageMinor)} of ₹${paiseToRupees(row.progress.targetMinor)}" +
                            if (row.progress.rolloverInMinor > 0) {
                                " (incl. ₹${paiseToRupees(row.progress.rolloverInMinor)} carried in)"
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (row.progress.coverageIncomplete) {
                        Text(
                            "! Partial transaction history — figures may be understated",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        state.forecast?.let { f ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Upcoming recurring (estimate)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "₹${paiseToRupees(f.expectedTotalMinor)} expected this period" +
                            if (f.includesUnconfirmed) " (includes unconfirmed patterns)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    f.upcoming.take(5).forEach { u ->
                        Text(
                            "• ${u.merchant ?: u.counterpartyNormalized ?: "Unknown payee"}: " +
                                "₹${paiseToRupees(u.expectedAmountMinor)}" +
                                (if (!u.confirmed) " (?)" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: com.example.fintrack.domain.model.ProgressStatus): String =
    "${status.symbol} ${status.label}"

private fun paiseToRupees(minor: Long): String {
    val r = minor / 100
    val p = minor % 100
    return if (p == 0L) "$r" else "$r.${p.toString().padStart(2, '0')}"
}
