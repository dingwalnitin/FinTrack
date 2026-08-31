package com.example.fintrack.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.enrichment.LlmProcessingService

@Composable
fun LlmProgressSummary(progress: LlmProcessingService.Progress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Triage ${progress.triageProcessed} / ${progress.triageTotal} SMS",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = {
                if (progress.triageTotal > 0) {
                    (progress.triageProcessed.toFloat() / progress.triageTotal).coerceIn(0f, 1f)
                } else 0f
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Batches ${progress.triageBatchesCompleted} / ${progress.triageBatchesTotal}  ·  " +
                "Batch SMS ${progress.batchTriaged}  ·  Direct SMS ${progress.directTriaged}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val triageDone = progress.triageTotal > 0 && progress.triageProcessed >= progress.triageTotal
        Text(
            if (triageDone) {
                "Transaction extraction ${progress.extractionProcessed} / ${progress.extractionTotal}"
            } else {
                // Triage is still feeding this phase, so the denominator grows.
                "Transaction extraction ${progress.extractionProcessed} / " +
                    "${progress.extractionTotal} candidates found so far"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = {
                if (progress.extractionTotal > 0) {
                    (progress.extractionProcessed.toFloat() / progress.extractionTotal).coerceIn(0f, 1f)
                } else 0f
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Overall ${progress.processed} / ${progress.total}  ·  " +
                "${progress.succeeded} discovered  ·  ${progress.failed} failed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}