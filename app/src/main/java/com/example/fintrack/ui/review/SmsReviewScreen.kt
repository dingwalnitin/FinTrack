package com.example.fintrack.ui.review

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.review.SmsReviewViewModel
import com.example.fintrack.ui.common.BrandFilterChip
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Stage 13 (F) — SMS review screen.
 *
 * Lists SMS ingestion results (passed / failed / pending) with a status
 * filter. A failed/pending row can be re-run through the LLM as a single job
 * (bypassing the 90-day batch lookback). Raw SMS bodies are shown here
 * because this is the explicit manual review surface.
 */
@Composable
fun SmsReviewScreen(
    viewModel: SmsReviewViewModel,
) {
    val state by viewModel.state.collectAsState()
    val zone = ZoneId.systemDefault()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("SMS review", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${state.rows.size} SMS · ${state.counts[com.example.fintrack.data.db.LlmJobStates.TERMINAL_FAILED] ?: 0} failed · ${state.counts[com.example.fintrack.data.db.LlmJobStates.PENDING] ?: 0} pending",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        state.error?.let { err ->
            Text("Error: $err", color = Palette.Danger, modifier = Modifier.padding(bottom = 8.dp))
        }

        // Status filter
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                SmsReviewViewModel.StatusFilter.ALL to "All",
                SmsReviewViewModel.StatusFilter.FAILED to "Failed",
                SmsReviewViewModel.StatusFilter.PENDING to "Pending",
                SmsReviewViewModel.StatusFilter.SUCCEEDED to "Succeeded",
            ).forEach { (f, label) ->
                BrandFilterChip(
                    selected = state.filter == f,
                    onClick = { viewModel.setFilter(f) },
                    label = label,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val rows = viewModel.visibleRows
        if (rows.isEmpty()) {
            EmptyState("No SMS match this filter.")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rows, key = { it.rawSmsId }) { row ->
                FinTrackCard {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                row.sender ?: "Unknown sender",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                statusLabel(row.jobStatus),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor(row.jobStatus),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            row.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Instant.ofEpochMilli(row.receivedAtEpochMs).atZone(zone)
                                .format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        row.lastErrorClass?.let { err ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "last error: $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Warn,
                            )
                        }
                        if (row.jobStatus in setOf(
                                com.example.fintrack.data.db.LlmJobStates.RETRYABLE_FAILED,
                                com.example.fintrack.data.db.LlmJobStates.TERMINAL_FAILED,
                                com.example.fintrack.data.db.LlmJobStates.PENDING,
                            ) || row.jobStatus == null
                        ) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.rerunMessage(row.rawSmsId) }) {
                                Text("Re-run LLM")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: String?): String = when (status) {
    com.example.fintrack.data.db.LlmJobStates.SUCCEEDED -> "Interpreted"
    com.example.fintrack.data.db.LlmJobStates.RETRYABLE_FAILED -> "Retryable failed"
    com.example.fintrack.data.db.LlmJobStates.TERMINAL_FAILED -> "Failed"
    com.example.fintrack.data.db.LlmJobStates.PENDING -> "Pending"
    com.example.fintrack.data.db.LlmJobStates.CLAIMED -> "Processing"
    com.example.fintrack.data.db.LlmJobStates.RUNNING -> "Processing"
    else -> "Not processed"
}

@Composable
private fun statusColor(status: String?): androidx.compose.ui.graphics.Color = when (status) {
    com.example.fintrack.data.db.LlmJobStates.SUCCEEDED -> Palette.Income
    com.example.fintrack.data.db.LlmJobStates.TERMINAL_FAILED -> Palette.Danger
    com.example.fintrack.data.db.LlmJobStates.RETRYABLE_FAILED -> Palette.Warn
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
