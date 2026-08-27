package com.example.fintrack.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.ImportConflict
import com.example.fintrack.domain.model.ImportPreview
import com.example.fintrack.domain.model.MergePolicy

/**
 * Stage 11 P23 #5 — import conflict-resolution UI (module 162).
 *
 * Shows the preview (new / identical / conflicting counts per dataset) and,
 * for each real conflict, the differing fields with an explicit per-row or
 * global choice: Merge (keep live + add new rows), Add-only, or
 * Replace-selected. Nothing is committed until the user presses the
 * confirmation button; Cancel aborts without any write.
 */
@Composable
fun ImportPreviewDialog(
    preview: ImportPreview,
    onConfirm: (MergePolicy, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedReplacements by remember { mutableStateOf(setOf<String>()) }
    val realConflicts = preview.realConflicts()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import preview") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${preview.totalNew()} new · ${preview.totalIdentical()} identical " +
                        "(re-import safe) · ${realConflicts.size} conflict(s)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider()

                if (realConflicts.isEmpty()) {
                    Text(
                        "No conflicts. Existing rows are kept; only new rows are added.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Conflicting rows:", style = MaterialTheme.typography.labelLarge)
                    ConflictList(
                        conflicts = realConflicts,
                        selected = selectedReplacements,
                        onToggle = { id ->
                            selectedReplacements =
                                if (id in selectedReplacements) selectedReplacements - id
                                else selectedReplacements + id
                        },
                    )
                }

                if (preview.missingReferences.isNotEmpty()) {
                    Text(
                        "Unresolved references: ${preview.missingReferences.size} " +
                            "(these rows will be skipped)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val policy =
                    if (realConflicts.isEmpty() || selectedReplacements.isEmpty()) MergePolicy.KEEP_LIVE
                    else MergePolicy.REPLACE_WITH_IMPORTED
                onConfirm(policy, selectedReplacements)
            }) {
                Text(
                    if (selectedReplacements.isEmpty()) "Merge (add new)" 
                    else "Apply (${selectedReplacements.size} replaced)",
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConflictList(
    conflicts: List<ImportConflict>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column {
        conflicts.take(6).forEach { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = c.stableId in selected,
                    onCheckedChange = { onToggle(c.stableId) },
                    modifier = Modifier.semantics {
                        contentDescription = "Replace ${c.dataset.name} row: ${c.differenceSummary}"
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(c.dataset.name, style = MaterialTheme.typography.labelMedium)
                    Text(
                        c.differenceSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (conflicts.size > 6) {
            Text(
                "…and ${conflicts.size - 6} more",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Export panel used from Settings → Data lifecycle.
 * Password field appears only when encrypted export is chosen.
 */
@Composable
fun ExportPanel(
    onExport: (encrypted: Boolean, password: CharArray?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var encrypted by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Encrypt export with password")
            androidx.compose.material3.Switch(
                checked = encrypted,
                onCheckedChange = { encrypted = it },
                modifier = Modifier.semantics {
                    contentDescription = "Encrypt export: ${if (encrypted) "on" else "off"}"
                },
            )
        }
        if (encrypted) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Export password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (password.isNotEmpty() && password.length < 8) {
                Text(
                    "Use at least 8 characters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Button(
            onClick = { onExport(encrypted, if (encrypted) password.toCharArray() else null) },
            enabled = !encrypted || password.length >= 8,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(if (encrypted) "Export encrypted" else "Export")
        }
    }
}
