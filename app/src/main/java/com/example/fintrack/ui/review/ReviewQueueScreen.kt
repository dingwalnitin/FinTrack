package com.example.fintrack.ui.review

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.review.ReviewQueueViewModel
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.IconBadge
import com.example.fintrack.ui.theme.Palette

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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Review queue", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${items.size} item(s) need attention",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (items.isEmpty()) {
            EmptyState("Nothing to review. All clear.")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                FinTrackCard {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(
                            icon = Icons.Filled.WarningAmber,
                            containerColor = Palette.Warn.copy(alpha = 0.18f),
                            tint = Palette.Warn,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.reason.name.replace('_', ' '),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            // Explanation of why review is needed; states missing evidence explicitly.
                            Text(
                                item.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
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
}
