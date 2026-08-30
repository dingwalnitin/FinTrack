package com.example.fintrack.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.backup.BackupViewModel

/**
 * Stage 11 P23 — backup & restore surface (recovery drill entry point).
 *
 * Documented restore workflow (module 163):
 *  1. Paste or load the export payload (plaintext FTBACKUP1 or encrypted
 *     FTBACKUP1ENC1 envelope).
 *  2. Enter the password when the payload is encrypted.
 *  3. Review the preview dialog: new / identical / conflict counts.
 *  4. Choose Merge (default), Replace-selected, or Cancel.
 *  5. Commit runs in one transaction; the result is audited.
 */
@Composable
fun BackupRestoreScreen(viewModel: BackupViewModel) {
    val state by viewModel.state.collectAsState()
    var payload by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Backup & restore", style = MaterialTheme.typography.titleLarge)
        Text(
            "Exports contain your financial history, never provider secrets, " +
                "and stay on this device unless you share the file yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Export", style = MaterialTheme.typography.labelLarge)
        ExportPanel(onExport = { encrypted, pwd -> viewModel.export(encrypted, pwd) })

        state.let { s ->
            if (s is BackupViewModel.UiState.ExportReady) {
                Text(
                    if (s.encrypted) "Encrypted export ready (${s.payload.length} chars)." 
                    else "Export ready (${s.payload.length} chars).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()
        Text("Restore", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            label = { Text("Paste backup payload") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (encrypted backups only)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.importPayload(
                    payload,
                    password.takeIf { it.isNotEmpty() }?.toCharArray(),
                )
            },
            enabled = payload.isNotBlank(),
        ) { Text("Validate & preview") }

        when (val s = state) {
            is BackupViewModel.UiState.ValidationFailed -> {
                s.reasons.forEach { reason ->
                    Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            is BackupViewModel.UiState.PreviewReady -> {
                ImportPreviewDialog(
                    preview = s.preview,
                    onConfirm = { policy, replaceIds -> viewModel.confirmImport(policy, replaceIds) },
                    onDismiss = { viewModel.cancelImport() },
                )
            }
            is BackupViewModel.UiState.Committed -> Text(
                "Restored: ${s.result.insertedByDataset.values.sum()} added, " +
                    "${s.result.replacedByDataset.values.sum()} replaced.",
                color = MaterialTheme.colorScheme.primary,
            )
            is BackupViewModel.UiState.Aborted -> Text(
                "Import aborted: ${s.reason}",
                color = MaterialTheme.colorScheme.error,
            )
            is BackupViewModel.UiState.Failed -> Text(
                s.reason,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {}
        }

        OutlinedButton(onClick = { viewModel.reset() }) { Text("Done") }
    }
}
