package com.example.fintrack.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.ai.AiQueryViewModel

/**
 * Stage 10 / P21 — AI query screen.
 *
 * Shows the interpreted date range BEFORE risky/ambiguous execution, renders
 * grounded, cited summaries with explicit coverage qualifications, and never
 * hides refusals or errors.
 */
@Composable
fun AiQueryScreen(viewModel: AiQueryViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ask your finances", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "AI query input" },
            label = { Text("e.g. spending by category last month") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.run() }, enabled = !state.loading) {
                Text(if (state.loading) "Running…" else "Ask")
            }
            if (state.query.isNotBlank()) {
                OutlinedButton(onClick = viewModel::clear) { Text("Clear") }
            }
        }

        state.refusalMessage?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.error?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.interpretedRangeLabel?.let {
            Text(it, style = MaterialTheme.typography.labelMedium)
        }

        state.summary?.let { summary ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.claims.forEach { claim ->
                        val citationLabel = when (val c = claim.citation) {
                            is com.example.fintrack.domain.ai.AiSummaryGenerator.SummaryClaim.Citation.Aggregate ->
                                "[agg:${c.key ?: "overall"}]"
                            is com.example.fintrack.domain.ai.AiSummaryGenerator.SummaryClaim.Citation.Transaction ->
                                "[txn:${c.transactionId.take(8)}]"
                            is com.example.fintrack.domain.ai.AiSummaryGenerator.SummaryClaim.Citation.Coverage ->
                                "[coverage]"
                            else -> ""
                        }
                        Text("$citationLabel ${claim.text}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (summary.isQualified) {
                        summary.qualifications().forEach { q ->
                            Text(
                                "⚠ $q",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }

        resultRows(state)
    }

    if (state.needsConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingPlan,
            title = { Text("Confirm date range") },
            text = { Text(state.confirmationReason ?: "") },
            confirmButton = {
                Button(onClick = viewModel::confirmPendingPlan) { Text("Run query") }
            },
            dismissButton = {
                OutlinedButton(onClick = viewModel::cancelPendingPlan) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun resultRows(state: AiQueryViewModel.State) {
    val result = state.result ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${result.totalMatching} matching transactions" +
                    (if (result.hasMore) " (showing first ${result.rows.size})" else ""),
                style = MaterialTheme.typography.titleSmall,
            )
            result.aggregates.forEach { agg ->
                val key = agg.key ?: "Uncategorized / overall"
                Text(
                    "$key — net ${agg.netMinor} minor (${agg.count} tx)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
