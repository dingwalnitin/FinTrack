package com.example.fintrack.ui.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.repository.IngestionProgress
import com.example.fintrack.application.enrichment.LlmProcessingService
import com.example.fintrack.ui.common.LlmProgressSummary

/**
 * Consent / progress screen for SMS evidence acquisition.
 *
 * Per the prompt:
 *  - Explains why SMS access is needed (personal money tracking, on-device).
 *  - Reacts non-destructively to revocation: already captured raw evidence
 *    is preserved; the status pill switches to REVOKED with a clear message.
 *  - Requests only the permissions needed for the selected distribution path
 *    (READ_SMS is hard-capped at sdk 32, RECEIVE_SMS is requested once).
 *
 * The screen exposes only aggregate progress (counts + status) — never a
 * per-message recomposition. While older messages process, the rest of the
 * app remains fully usable.
 */
@Composable
fun SmsConsentScreen(
    state: SmsConsentState,
    onRequestPermission: () -> Unit,
    onStartBackfill: () -> Unit,
    onPauseBackfill: () -> Unit,
    onRevokeHandled: () -> Unit,
    llmProgress: LlmProcessingService.Progress? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SMS evidence", style = MaterialTheme.typography.titleLarge)
        Text(
            "FinTrack uses SMS messages only as raw evidence for your personal money tracking. " +
                "We never modify or delete the messages on your phone. Everything stays on this device."
        )
        Bullet("Only financial SMS are stored as immutable evidence.")
        Bullet("Raw messages are never sent off-device; you decide when interpretation runs.")
        Bullet("You can revoke SMS access at any time. Already-captured evidence is preserved.")

        HorizontalDivider()

        Text("Status", style = MaterialTheme.typography.titleMedium)
        Text(
            text = describeStatus(state),
            modifier = Modifier.semantics { contentDescription = "Ingestion status: ${describeStatus(state)}" },
        )
        Text(
            "Captured raw messages: ${state.progress.totalPersisted}",
            modifier = Modifier.semantics {
                contentDescription = "Captured ${state.progress.totalPersisted} messages"
            },
        )

        Spacer(Modifier.padding(top = 8.dp))

        if (llmProgress != null && (llmProgress.running || llmProgress.total > 0)) {
            HorizontalDivider()
            Text("AI processing", style = MaterialTheme.typography.titleMedium)
            LlmProgressSummary(llmProgress)
            llmProgress.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        when (state.phase) {
            SmsConsentPhase.NEEDS_CONSENT -> {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
                ) { Text("Allow SMS access") }
                Text(
                    "Allowing will request both the SMS read permission (for backfilling history) " +
                        "and the SMS receive permission (so we can capture new messages as raw evidence).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SmsConsentPhase.READY -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStartBackfill,
                        modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                    ) { Text("Backfill history") }
                    OutlinedButton(
                        onClick = onPauseBackfill,
                        modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                    ) { Text("Pause") }
                }
                Text(
                    "Backfill runs in the background. You can keep using the app while older " +
                        "messages are being captured.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SmsConsentPhase.REVOKED -> {
                OutlinedButton(
                    onClick = onRequestPermission,
                    modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
                ) { Text("Request access again") }
                OutlinedButton(
                    onClick = onRevokeHandled,
                    modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
                ) { Text("I understand; continue") }
                Text(
                    "Access was revoked. Already-captured raw evidence is preserved on this device. " +
                        "New incoming messages will not be captured until access is granted again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("• ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun describeStatus(state: SmsConsentState): String = when {
    state.progress.status == "REVOKED" -> "Permission revoked"
    state.progress.status == "RUNNING" -> "Running"
    state.progress.status == "PAUSED" -> "Paused"
    state.progress.status == "COMPLETE" -> "Backfill complete"
    state.progress.status == "FAILED" -> "Failed: ${state.progress.lastError ?: "unknown error"}"
    state.hasPermission -> "Idle (permission granted)"
    else -> "Permission not granted"
}

enum class SmsConsentPhase { NEEDS_CONSENT, READY, REVOKED }

data class SmsConsentState(
    val hasPermission: Boolean,
    val phase: SmsConsentPhase,
    val progress: IngestionProgress,
)
