package com.example.fintrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.fintrack.llm.LlmConfig
import com.example.fintrack.llm.LlmKeyEntry
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.IconBadge
import com.example.fintrack.ui.theme.Palette

/**
 * Dedicated, full-screen LLM (AI interpretation) settings.
 *
 * Supports multi-API-key pool management with automatic rate-limit scaling,
 * 90-day batch lookback notice, and live connectivity probes.
 */
@Composable
fun LlmSettingsScreen(
    viewModel: LlmSettingsViewModel,
    onBack: () -> Unit = {},
) {
    val config by viewModel.config.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val saved by viewModel.saved.collectAsState()

    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var modelId by remember { mutableStateOf(config.modelId) }

    // Dialog state for adding a new key
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var newKeyText by remember { mutableStateOf("") }
    var newKeyLabel by remember { mutableStateOf("") }
    var showNewKeySecret by remember { mutableStateOf(false) }

    // Dialog state for removing a key
    var keyToDelete by remember { mutableStateOf<LlmKeyEntry?>(null) }

    fun commitEndpoint() {
        viewModel.updateConfig(config.copy(baseUrl = baseUrl.trim(), modelId = modelId.trim()))
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(icon = Icons.Filled.AutoAwesome, containerColor = Palette.Violet)
                Column {
                    Text("AI interpretation", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Configure the OpenAI-compatible model that turns your SMS into transactions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Endpoint Configuration Card
        item {
            FinTrackCard {
                Text("Endpoint", style = MaterialTheme.typography.titleMedium)
                Text(
                    "FinTrack connects to any OpenAI-compatible Chat Completions endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; commitEndpoint() },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com") },
                    supportingText = { Text("e.g. https://api.openai.com or https://your-gateway.example") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it; commitEndpoint() },
                    label = { Text("Model ID") },
                    placeholder = { Text("gpt-4o-mini") },
                    supportingText = { Text("Model identifier, e.g. gpt-4o-mini, llama-3.3-70b") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // API Key Pool Management Card
        item {
            FinTrackCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("API Key Pool", style = MaterialTheme.typography.titleMedium)
                        val activeCount = config.activeKeys.size
                        Text(
                            if (activeCount > 0) {
                                "$activeCount active keys • ${activeCount * 25} req/min • ${activeCount * 1000} req/day"
                            } else {
                                "No active keys configured"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (activeCount > 0) Palette.Income else Palette.Danger,
                        )
                    }
                    Button(
                        onClick = {
                            newKeyText = ""
                            newKeyLabel = ""
                            showNewKeySecret = false
                            showAddKeyDialog = true
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Key", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Add Key")
                    }
                }

                if (config.keys.isEmpty()) {
                    Text(
                        "No API keys configured yet. Add at least one key to enable AI interpretation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        config.keys.forEach { keyEntry ->
                            KeyEntryRow(
                                entry = keyEntry,
                                onToggle = { enabled ->
                                    viewModel.toggleKeyEnabled(keyEntry.id, enabled)
                                },
                                onDelete = {
                                    keyToDelete = keyEntry
                                },
                            )
                        }
                    }
                }
            }
        }

        // Save & Test Connection Actions
        item {
            FinTrackCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            commitEndpoint()
                            viewModel.save()
                        },
                        enabled = baseUrl.isNotBlank() && modelId.isNotBlank() && config.activeKeys.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Save Settings") }

                    OutlinedButton(
                        onClick = {
                            commitEndpoint()
                            viewModel.testConnection()
                        },
                        enabled = !testing && baseUrl.isNotBlank() && modelId.isNotBlank() && config.activeKeys.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Palette.Violet,
                            )
                        } else {
                            Text("Test connection")
                        }
                    }
                }
            }
        }

        if (saved) {
            item {
                StatusCard(
                    color = Palette.Income,
                    icon = Icons.Filled.CheckCircle,
                    title = "Configuration saved",
                    message = "The next LLM scan will use these pooled keys and endpoint.",
                )
            }
        }

        when (val result = testResult) {
            is LlmSettingsViewModel.TestResult.Success -> item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusCard(
                        color = Palette.Income,
                        icon = Icons.Filled.CheckCircle,
                        title = "Connection OK",
                        message = "Model \"${result.diagnostics.modelId}\" replied successfully. Ready to scan.",
                    )
                    ConnectionDiagnosticsCard(result.diagnostics)
                }
            }
            is LlmSettingsViewModel.TestResult.Failure -> item {
                StatusCard(
                    color = Palette.Danger,
                    icon = Icons.Filled.ErrorOutline,
                    title = "Connection failed",
                    message = result.message,
                )
            }
            LlmSettingsViewModel.TestResult.Incomplete -> item {
                StatusCard(
                    color = Palette.Warn,
                    icon = Icons.Filled.ErrorOutline,
                    title = "Incomplete config",
                    message = "Set Base URL, Model ID, and add at least one active API key.",
                )
            }
            null -> {}
        }

        // Batch Scan Lookback & Privacy Information Card
        item {
            FinTrackCard {
                Text("Scan Scope & Privacy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• Batch Scan: Scans historical SMS from the last 3 months (90 days) to optimize token quota.\n" +
                        "• Real-Time Ingestion: New incoming SMS are parsed immediately as they arrive.\n" +
                        "• Zero-PII Leak: Full phone numbers, OTPs, and bank accounts are stripped/masked before leaving the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }

    // Add Key Dialog
    if (showAddKeyDialog) {
        AlertDialog(
            onDismissRequest = { showAddKeyDialog = false },
            title = { Text("Add API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newKeyLabel,
                        onValueChange = { newKeyLabel = it },
                        label = { Text("Label / Nickname (optional)") },
                        placeholder = { Text("e.g. Work Key, Key 2") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newKeyText,
                        onValueChange = { newKeyText = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-…") },
                        singleLine = true,
                        visualTransformation = if (showNewKeySecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Text(
                                if (showNewKeySecret) "Hide" else "Show",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clickable { showNewKeySecret = !showNewKeySecret },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newKeyText.isNotBlank()) {
                            viewModel.addKey(newKeyText, newKeyLabel)
                            showAddKeyDialog = false
                        }
                    },
                    enabled = newKeyText.isNotBlank(),
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddKeyDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Delete Confirmation Dialog
    keyToDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = { Text("Remove API Key?") },
            text = { Text("Remove \"${key.label}\" (${key.maskedKey}) from the pool?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeKey(key.id)
                        keyToDelete = null
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun KeyEntryRow(
    entry: LlmKeyEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                tint = if (entry.enabled) Palette.Violet else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = entry.maskedKey,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Switch(
                checked = entry.enabled,
                onCheckedChange = onToggle,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete key",
                    tint = Palette.Danger,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(color: Color, icon: ImageVector, title: String, message: String) {
    FinTrackCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectionDiagnosticsCard(
    diagnostics: com.example.fintrack.llm.ChatCompletionsProvider.ConnectionDiagnostics,
) {
    FinTrackCard(containerColor = Palette.SurfaceElevated) {
        Text("Test response", style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(
                diagnostics.reply,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Latency: ${diagnostics.latencyMs}ms", style = MaterialTheme.typography.bodySmall)
            Text(
                "Speed: ${"%.1f".format(diagnostics.tokensPerSecond)} t/s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
