package com.example.fintrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.fintrack.llm.LlmConfig
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.IconBadge
import com.example.fintrack.ui.theme.Palette

/**
 * Dedicated, full-screen LLM (AI interpretation) settings.
 *
 * Replaces the cramped inline config fields that used to live in the main
 * Settings screen. Uses the same shared [LlmConfigStore] so any config saved
 * here is what the LLM providers read on the next request.
 *
 * Also exposes a live "Test connection" button that sends a tiny probe
 * request to the configured Chat Completions endpoint so the user can confirm
 * the base URL, API key and model id actually work *before* kicking off a
 * full SMS scan.
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

    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var modelId by remember { mutableStateOf(config.modelId) }

    fun commit() {
        viewModel.updateConfig(LlmConfig(baseUrl.trim(), apiKey.trim(), modelId.trim()))
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

        item {
            FinTrackCard {
                Text("Connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    "FinTrack talks to any OpenAI-compatible Chat Completions endpoint. " +
                        "The SMS body text (unmodified) is sent, with the sender identifier hashed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; commit() },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com") },
                    supportingText = { Text("e.g. https://api.openai.com or https://your-gateway.example") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; commit() },
                    label = { Text("API key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(
                            if (showKey) "Hide" else "Show",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(8.dp)
                                .clickable { showKey = !showKey },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it; commit() },
                    label = { Text("Model ID") },
                    placeholder = { Text("gpt-4o-mini") },
                    supportingText = { Text("Any model id supported by your endpoint, e.g. gpt-4o-mini, llama-3.3-70b") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            commit()
                            viewModel.save()
                        },
                        enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }

                    OutlinedButton(
                        onClick = {
                            commit()
                            viewModel.testConnection()
                        },
                        enabled = !testing && baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank(),
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
                    message = "The next LLM scan will use these values.",
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
                    message = "Fill in all three fields before testing.",
                )
            }
            null -> {}
        }

        item {
            FinTrackCard {
                Text("What gets sent", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The text of your SMS is sent, unmodified, to your configured endpoint to extract " +
                        "transactions. Sender identifiers are hashed, and your API key is stored " +
                        "only on this device. Use a trusted endpoint you control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
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
        Text("Developer metrics", style = MaterialTheme.typography.titleSmall)
        val completion = if (diagnostics.completionTokensEstimated) {
            "~${diagnostics.completionTokens} (estimated)"
        } else {
            "${diagnostics.completionTokens}"
        }
        val throughputPrefix = if (diagnostics.completionTokensEstimated) "~" else ""
        Text(
            "HTTP ${diagnostics.statusCode}  ·  ${if (diagnostics.streamed) "streaming" else "non-streaming"}\n" +
                "Latency: ${diagnostics.latencyMs} ms\n" +
                "TTFT: ${diagnostics.timeToFirstTokenMs?.let { "$it ms" } ?: "unavailable (needs streaming)"}\n" +
                "Throughput: $throughputPrefix${"%.1f".format(diagnostics.tokensPerSecond)} tokens/s" +
                " over ${diagnostics.throughputBasis}\n" +
                "Tokens: prompt ${diagnostics.promptTokens ?: "unreported"}, " +
                "completion $completion, total ${diagnostics.totalTokens ?: "unreported"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (diagnostics.completionTokensEstimated) {
                "This server reported no token usage, so completion tokens and " +
                    "throughput are approximated from response length. Figures come from a " +
                    "short ${diagnostics.maxTokens}-token probe and will differ from a real SMS scan."
            } else {
                "Figures come from a short ${diagnostics.maxTokens}-token probe and will " +
                    "differ from a real SMS scan, which uses much longer prompts."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Raw transport response", style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Text(
                diagnostics.rawResponse,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small card used to surface save / test result states. */
@Composable
private fun StatusCard(
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
) {
    FinTrackCard(containerColor = Palette.SurfaceElevated) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = color)
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
