package com.example.fintrack.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.review.ReviewQueueViewModel

/**
 * Stage 7 P15 #1: review queue screen.
 *
 * Shows open review items in priority order with a concise explanation of
 * WHY each needs attention. Resolve / Dismiss are explicit user actions;
 * both are recorded so the audit trail shows the item was seen.
 *
 * The general uncertainty UX rule applies: explanations state what evidence
 * is missing — they never fabricate one.
 */
@Composable
fun ReviewQueueScreen(
    viewModel: ReviewQueueViewModel,
    onOpenTransaction: (String) -> Unit,
) {
    val items by viewModel.openItems.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Review queue", style = MaterialTheme.typography.titleLarge)
        Text(
            "${items.size} item(s) need attention",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (items.isEmpty()) {
            Text("Nothing to review. All clear.")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.reason.name, style = MaterialTheme.typography.titleSmall)
                        // Explanation of why review is needed; states missing evidence explicitly.
                        Text(item.explanation, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenTransaction(item.transactionId) }) {
                                Text("Open")
                            }
                            OutlinedButton(onClick = { viewModel.resolve(item.id) }) {
                                Text("Resolve")
                            }
                            OutlinedButton(onClick = { viewModel.dismiss(item.id) }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }
}
