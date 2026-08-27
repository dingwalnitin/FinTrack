package com.example.fintrack.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.transactions.TransferCandidatesViewModel

/**
 * P11 #1: transfer-candidates Review screen. Shows ambiguous (DEBIT, CREDIT)
 * pairs the engine scored in the REVIEW band; AUTO_LINK pairs are already
 * paired and never shown here.
 */
@Composable
fun TransferCandidatesScreen(
    accountIds: List<String>,
    viewModel: TransferCandidatesViewModel,
) {
    LaunchedEffect(accountIds) { viewModel.load(accountIds) }
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        TransferCandidatesViewModel.State.Loading ->
            Text("Loading…", Modifier.padding(16.dp))
        TransferCandidatesViewModel.State.Empty ->
            Text("No transfer candidates found.", Modifier.padding(16.dp))
        is TransferCandidatesViewModel.State.Error ->
            Text("Error: ${s.message}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        is TransferCandidatesViewModel.State.Ready -> LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Transfer candidates for review", style = MaterialTheme.typography.titleLarge) }
            items(s.proposals, key = { it.debitEventId + it.creditEventId }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Score: %.2f (${p.verdict})".format(p.score), style = MaterialTheme.typography.titleMedium)
                        Text("Debit: ${p.debitEventId}", style = MaterialTheme.typography.bodySmall)
                        Text("Credit: ${p.creditEventId}", style = MaterialTheme.typography.bodySmall)
                        val reasons = p.signals.filterValues { it > 0.0 }
                            .map { (k, v) -> "$k=${"%.2f".format(v)}" }
                            .joinToString(", ")
                        Text("Signals: $reasons", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
